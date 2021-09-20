package com.evernym.verity.actor.metrics

import akka.actor.Props
import akka.event.LoggingReceive
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.GetSponsorRel
import com.evernym.verity.actor.agent.{AgentActivityRecorded, SponsorRel}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.ActorMessage
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
    with DefaultPersistenceEncryption {

  var state: State = (new State).withActivityWindow(ConfigUtil.findActivityWindow(appConfig))
  logger.info(s"[$persistenceId] started activity tracker with windows: ${state.activityWindow}")

  /**
   * command handler
   */
  val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case activity: AgentActivity if state.sponsorReady() => handleRecordAgentActivity(activity)
    case activity: AgentActivity                         => needsSponsor(activity)

    //TODO: only used by test, should find better way to handle the need
    case activityWindow: ActivityWindow                  => updateActivityWindows(activityWindow)
  }

  val waitingForSponsor: Receive = LoggingReceive.withLabel("waitingForSponsor") {
    case sponsorRel: SponsorRel =>
      updateSponsorRel(sponsorRel)
      setNewReceiveBehaviour(receiveCmd)
      unstashAll()

    case msg =>
      logger.debug(s"[$persistenceId] stashing $msg")
      stash()
  }

  //event handler
  val receiveEvent: Receive = {
    case aar: AgentActivityRecorded =>
      state = state.withAgentActivity(aar.stateKey, AgentActivity(aar))

    case other => //only kept to handle any legacy events if at all persisted
      logger.info("unhandled activity tracker event found: " + other.getClass.getSimpleName)
  }

  private def updateActivityWindows(activityWindow: ActivityWindow): Unit = {
    state = state.withActivityWindow(activityWindow)
  }

  private def updateSponsorRel(sponsorRel: SponsorRel): Unit = {
    if (sponsorRel.equals(SponsorRel.empty)) state = state.withSponsorRetrievalAttempted()
    else state = state.withSponsorRel(sponsorRel)
  }

  /**
   * Sponsor details not set, go to agent and retrieve
   */
  private def needsSponsor(activity: AgentActivity): Unit = {
    logger.trace(s"[$persistenceId] getting sponsor info, activity: $activity")
    stash()
    setNewReceiveBehaviour(waitingForSponsor)
    agentMsgRouter.forward(InternalMsgRouteParam(activity.domainId, GetSponsorRel), self)
  }

  /**
   * 1. Filter available windows if activity needs to be recorded for it
   *    a. check if new activity within window
   *    b. validate relationship
   * 2. check activity type (AU, AR)
   * 4. Record accordingly
   */
  private def handleRecordAgentActivity(activity: AgentActivity): Unit = {
    logger.debug(s"[$persistenceId] request to record activity: $activity, windows: ${state.activityWindow}")
    state
     .activityWindow
     .rules
     .filter(rule => isUntrackedMetric(rule, activity))
     .filter(rule => isValidRelationship(rule, activity))
     .foreach(rule => recordActivity(rule, activity))
 }

  //decides if the given 'newActivity' needs to be recorded for the given window rule if:
  //  a. it was never recorded or
  //  b. "not recorded for the month" or "expired"
  private def isUntrackedMetric(rule: ActivityWindowRule, newActivity: AgentActivity): Boolean = {
    val lastWindowActivity = state.activity(rule, newActivity.id(rule.activityType))
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

  private def isValidRelationship(rule: ActivityWindowRule, activity: AgentActivity): Boolean = {
    rule.activityType match {
      case ActiveRelationships if activity.relId.isEmpty => false
      case _ => true
    }
  }

  /*
    1. Possible metrics to be recorded: "active users", "active relationships"
    2. Each metrics will be tagged with either a "domainId" or "sponsorId"
   */
  private def recordActivity(rule: ActivityWindowRule, activity: AgentActivity): Unit = {
    logger.info(s"[$persistenceId] track activity: $activity, window: $rule, tags: ${agentTags(rule, activity.domainId)}")
    metricsWriter.gaugeIncrement(rule.activityType.metricName, tags = agentTags(rule, activity.domainId))
    val event = AgentActivityRecorded(
      activity.domainId,
      activity.timestamp,
      state.sponsorRel,
      activity.activityType,
      activity.relId.getOrElse(""),
      state.key(rule, activity.id(rule.activityType)),
    )

    writeAndApply(event)
  }

  private def agentTags(rule: ActivityWindowRule, domainId: DomainId): Map[String, String] = {
    //TODO: not sure about why the "actual activity type" (like 'MSGS', all possible outgoing messages)
    // doesn't matter in the tags determination
    rule.activityType match {
      case ActiveUsers =>
        ActiveUsers.tags(
          state.sponsorRel.getOrElse(SponsorRel.empty).sponsorId,
          rule.frequencyType
        )
      case ActiveRelationships =>
        ActiveRelationships.tags(
          domainId,
          rule.frequencyType,
          state.sponsorRel.getOrElse(SponsorRel.empty).sponseeId
        )
    }
  }

  override def futureExecutionContext: ExecutionContext = executionContext
}

/**
 * actor persistent state object
 * (should be replaced by proto message when we switch to use snapshotting)
 */
class State(_activities: Map[ActivityKey, AgentActivity] = Map.empty,
            _activityWindow: ActivityWindow = ActivityWindow(Set()),
            _sponsorRel: Option[SponsorRel] = None,
            _isSponsorRetrievalAttempted: Boolean = false) {

  def copy(activities: Map[ActivityKey, AgentActivity] = _activities,
           activityWindow: ActivityWindow = _activityWindow,
           sponsorRel: Option[SponsorRel] = None,
           isSponsorRetrievalAttempted: Boolean = _isSponsorRetrievalAttempted): State =
    new State(activities, activityWindow, sponsorRel, isSponsorRetrievalAttempted)

  def sponsorRel: Option[SponsorRel] = _sponsorRel
  def withSponsorRel(sponsorRel: SponsorRel): State =
    copy(sponsorRel=Some(sponsorRel))
  /**
   * A sponsor can be undefined (If activity occurs and there is no sponsor)
   */
  def sponsorReady(): Boolean = sponsorRel.isDefined || _isSponsorRetrievalAttempted
  def withSponsorRetrievalAttempted(): State = copy(isSponsorRetrievalAttempted = true)

  def activityWindow: ActivityWindow = _activityWindow
  def withActivityWindow(activityWindow: ActivityWindow): State = copy(activityWindow=activityWindow)

  def activity(window: ActivityWindowRule, id: Option[String]): Option[AgentActivity] = _activities.get(key(window, id))
  def withAgentActivity(key: ActivityKey, activity: AgentActivity): State = copy(activities=_activities + (key -> activity))

  def key(window: ActivityWindowRule, id: Option[String]=None): ActivityKey =
    s"${window.activityType.metricName}-${window.frequencyType.toString}-${id.getOrElse("")}"
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

/** ActivityTracker Commands */
trait ActivityTrackingCommand extends ActorMessage
final case class ActivityWindow(rules: Set[ActivityWindowRule]) extends ActivityTrackingCommand
final case class ActivityWindowRule(frequencyType: FrequencyType, activityType: ActivityType)

final case class AgentActivity(domainId: DidStr,
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

object AgentActivity {
  def apply(aar: AgentActivityRecorded): AgentActivity =
    new AgentActivity(
      aar.domainId,
      aar.timestamp,
      aar.activityType,
      if (aar.relId == "") None else Some(aar.relId)
    )
}

/** ActivityTracker Event Base Type */
trait ActivityEvent

