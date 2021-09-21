package com.evernym.verity.actor.metrics.activity_tracker

import akka.actor.Props
import akka.event.LoggingReceive
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.GetSponsorRel
import com.evernym.verity.actor.agent.{ActivityState, AgentActivity, AgentActivityRecorded, AgentDetailRecorded, AgentParam, LegacyAgentActivityRecorded, SponsorRel}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotterExt}
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.did.DidStr
import com.evernym.verity.observability.metrics.CustomMetrics.{AS_ACTIVE_USER_AGENT_COUNT, AS_USER_AGENT_ACTIVE_RELATIONSHIPS}
import com.evernym.verity.protocol.engine.DomainId
import com.evernym.verity.util.TimeUtil
import com.evernym.verity.util.TimeUtil.{IsoDateTime, dateAfterDuration, isDateExpired, toMonth}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/**
 Records an Agent's
  1. activity within a specified window
  2. active relationships within a specified window
 */
class ActivityTracker(override val appConfig: AppConfig,
                      agentMsgRouter: AgentMsgRouter,
                      executionContext: ExecutionContext)
  extends BasePersistentActor
    with DefaultPersistenceEncryption
    with SnapshotterExt[ActivityState]{

  var state: ActivityState = new ActivityState
  val activityWindow: ActivityWindow = ConfigUtil.findActivityWindow(appConfig)
  logger.info(s"[$persistenceId] started activity tracker with window: $activityWindow")

  /**
   * command handler
   */
  val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case raa: RecordAgentActivity if isAgentDetailRecorded => handleRecordAgentActivity(raa)
    case raa: RecordAgentActivity                          => needsSponsor(raa)
  }

  def waitingForSponsor(domainId: DomainId): Receive = LoggingReceive.withLabel("waitingForSponsor") {
    case sponsorRel: SponsorRel =>
      writeAndApply(AgentDetailRecorded(domainId, Option(sponsorRel)))
      setNewReceiveBehaviour(receiveCmd)
      unstashAll()

    case msg =>
      logger.debug(s"[$persistenceId] stashing $msg")
      stash()
  }

  //event handler
  val eventReceiver: Receive = {
    case adr: AgentDetailRecorded =>
      state = state.withAgentParam(AgentParam(adr.domainId, adr.sponsorRel))

    case aar: AgentActivityRecorded =>
      state = state.addActivities((aar.stateKey, AgentActivity(aar.timestamp, aar.activityType, Option(aar.relId))))
  }

  val legacyEventReceiver: Receive = {
    case laar: LegacyAgentActivityRecorded =>
      state = state
        .withAgentParam(AgentParam(laar.domainId, laar.sponsorRel))
        .addActivities((laar.stateKey, AgentActivity(laar.timestamp, laar.activityType, Option(laar.relId))))

    //only kept to handle any legacy events if at all persisted
    case other =>
      logger.info(s"[$persistenceId] unhandled activity tracker event found: " + other.getClass.getSimpleName)
  }

  val receiveEvent: Receive = eventReceiver orElse legacyEventReceiver

  /**
   * Sponsor details not set, go to agent and retrieve
   */
  private def needsSponsor(raa: RecordAgentActivity): Unit = {
    logger.trace(s"[$persistenceId] getting sponsor info, activity: $raa")
    stash()
    setNewReceiveBehaviour(waitingForSponsor(raa.domainId))
    agentMsgRouter.forward(InternalMsgRouteParam(raa.domainId, GetSponsorRel), self)
  }

  /**
   * 1. Filter available windows if activity needs to be recorded for it
   *    a. check if new activity within window
   *    b. validate relationship
   * 2. check activity type (AU, AR)
   * 4. Record accordingly
   */
  private def handleRecordAgentActivity(activity: RecordAgentActivity): Unit = {
    if (state.agentParam.map(_.domainId).contains(activity.domainId)) {
      logger.debug(s"[$persistenceId] request to record activity: $activity, windows: $activityWindow")
      activityWindow
        .rules
        .filter(rule => isUntrackedMetric(rule, activity))
        .filter(rule => isValidRelationship(rule, activity))
        .foreach(rule => recordActivity(rule, activity))
    } else {
      logger.warn(s"[$persistenceId] request received for domain ${activity.domainId} " +
        s"not matched with pre-set domain ${state.agentParam.map(_.domainId)}" )
    }
 }

  //decides if the given 'newActivity' needs to be recorded for the given window rule if:
  //  a. it was never recorded or
  //  b. "not recorded for the month" or "for the given window rule"
  private def isUntrackedMetric(rule: ActivityWindowRule, newActivity: RecordAgentActivity): Boolean = {
    val lastWindowActivity = activity(rule, newActivity.id(rule.trackActivityType))
    lastWindowActivity.forall { lastActivity =>
      rule.frequencyType match {
        case CalendarMonth =>
          //If timestamps are not in the same month, record new activity
          !toMonth(lastActivity.timestamp).equals(toMonth(newActivity.timestamp))

        case VariableDuration(duration) =>
          //If newActivity is >= the expiredDate, record new activity
          val expiredDate = dateAfterDuration(lastActivity.timestamp, duration)
          isDateExpired(newActivity.timestamp, expiredDate)
      }
    }
  }

  private def isValidRelationship(rule: ActivityWindowRule, activity: RecordAgentActivity): Boolean = {
    rule.trackActivityType match {
      case ActiveRelationships if activity.relId.isEmpty => false
      case _ => true
    }
  }

  /*
    1. Possible metrics to be recorded: "active users", "active relationships"
    2. Each metrics will be tagged with either a "domainId" or "sponsorId"
   */
  private def recordActivity(rule: ActivityWindowRule, activity: RecordAgentActivity): Unit = {
    logger.info(s"[$persistenceId] track activity: $activity, window: $rule, tags: ${agentTags(rule, activity.domainId)}")
    metricsWriter.gaugeIncrement(rule.trackActivityType.metricName, tags = agentTags(rule, activity.domainId))
    val event = AgentActivityRecorded(
      key(rule, activity.id(rule.trackActivityType)),
      activity.timestamp,
      activity.activityType,
      activity.relId.getOrElse("")
    )

    writeAndApply(event)
  }

  private def agentTags(rule: ActivityWindowRule, domainId: DomainId): Map[String, String] = {
    rule.trackActivityType match {
      case ActiveUsers =>
        ActiveUsers.tags(
          sponsorRel.getOrElse(SponsorRel.empty).sponsorId,
          rule.frequencyType
        )
      case ActiveRelationships =>
        ActiveRelationships.tags(
          domainId,
          rule.frequencyType,
          sponsorRel.getOrElse(SponsorRel.empty).sponseeId
        )
    }
  }

  def sponsorRel: Option[SponsorRel] = state.agentParam.flatMap(_.sponsorRel)
  /**
   * A sponsor can be undefined (If activity occurs and there is no sponsor)
   */
  def isAgentDetailRecorded: Boolean = state.agentParam.isDefined

  def activity(rule: ActivityWindowRule, id: Option[String]): Option[AgentActivity] =
    state.activities.get(key(rule, id))

  def key(rule: ActivityWindowRule, id: Option[String]=None): ActivityKey =
    s"${rule.trackActivityType.metricName}-${rule.frequencyType.toString}-${id.getOrElse("")}"

  override def futureExecutionContext: ExecutionContext = executionContext

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case s: ActivityState => state = s
  }

  override def snapshotState: Option[ActivityState] = {
    Option(state)
  }
}


object ActivityTracker {
 def props(implicit config: AppConfig, agentMsgRouter: AgentMsgRouter, executionContext: ExecutionContext): Props = {
  Props(new ActivityTracker(config, agentMsgRouter, executionContext))
 }
}

/** Types of agent activity that may be used for metrics
 *  ActiveUsers: Manages number of active users within a defined time window
 *  ActiveRelationships: Manages number of relationships a specific Agent (domainId) has within a defined window
 * */
trait ActivityType {
  def metricName: String
  def idType: String

  def tags(id: String, frequency: FrequencyType): Map[String, String] =
    Map(
      idType -> id,
      "frequency" -> frequency.toString
    )
}
trait AgentActivityType extends ActivityType

case object ActiveUsers extends AgentActivityType {
  def metricName: String = AS_ACTIVE_USER_AGENT_COUNT
  def idType: String = "sponsorId"
}
case object ActiveRelationships extends AgentActivityType {
  def metricName: String = AS_USER_AGENT_ACTIVE_RELATIONSHIPS
  def idType: String = "domainId"
  def tags(id: String, frequencyType: FrequencyType, sponseeId: String): Map[String, String] =
    super.tags(id, frequencyType) + ("sponseeId" -> sponseeId)
}

/** How often an "activity type" is recorded
 *  CalendarMonth   : January, February, ..., December
 *  VariableDuration: Any datetime range
 * */
trait FrequencyType
case object CalendarMonth extends FrequencyType {
  override def toString: String = s"monthly-${TimeUtil.toMonth(TimeUtil.nowDateString)}"
}
case class VariableDuration(duration: Duration) extends FrequencyType {
  override def toString: String = duration.toString
}
object VariableDuration {
  def apply(duration: String): VariableDuration = new VariableDuration(Duration(duration))
}

final case class ActivityWindow(rules: Set[ActivityWindowRule])
final case class ActivityWindowRule(frequencyType: FrequencyType, trackActivityType: ActivityType)

/** ActivityTracker Commands */
trait ActivityTrackingCommand extends ActorMessage
final case class RecordAgentActivity(domainId: DidStr,
                                     timestamp: IsoDateTime,
                                     activityType: String,
                                     relId: Option[String]=None) extends ActivityTrackingCommand {
  def id(activityType: ActivityType): Option[String] =
    activityType match {
      case ActiveUsers          => Some(domainId)
      case ActiveRelationships  => relId
      case _                    => None
    }
}

/** ActivityTracker Event Base Type */
trait ActivityEvent

