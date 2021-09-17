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
  logger.info(s"[$persistenceId] started activity tracker with windows: ${state.activityWindows}")

  /**
   * command handler
   */
  val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case activity: AgentActivity if state.sponsorReady() => recordAgentActivity(activity)
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
  }

  private def updateActivityWindows(activityWindow: ActivityWindow): Unit = {
    state = state.withActivityWindow(activityWindow)
  }

  private def updateSponsorRel(sponsorRel: SponsorRel): Unit = {
    if (sponsorRel.equals(SponsorRel.empty)) state = state.withAttemptedSponsorRetrieval()
    else state = state.withSponsorRel(sponsorRel)
  }

  /**
   * Sponsor details not set, go to agent and retrieve
   */
  def needsSponsor(activity: AgentActivity): Unit = {
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
  def recordAgentActivity(activity: AgentActivity): Unit = {
   logger.debug(s"[$persistenceId] request to record activity: $activity, windows: ${state.activityWindows}")
   state
     .activityWindows
     .windows
     .filter(window => isUntrackedMetric(window, activity))
     .filter(window => relationshipValidation(window, activity))
     .foreach(window => recordActivity(window, activity))
 }

  //decides if the given activity needs to be recorded for the given window rule if:
  //  a. it was never recorded or
  //  b. "not recorded for the month" or "expired"
  def isUntrackedMetric(windowRule: ActivityWindowRule, newActivity: AgentActivity): Boolean = {
    val lastWindowActivity = state.activity(windowRule, newActivity.id(windowRule.activityType))
    lastWindowActivity.forall { lastActivity =>
      windowRule.frequencyType match {
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

  def relationshipValidation(windowRule: ActivityWindowRule, activity: AgentActivity): Boolean =
    windowRule.activityType match {
      case ActiveRelationships if activity.relId.isEmpty => false
      case _ => true
    }

  /*
    1. Possible metrics to be recorded: "active users", "active relationships"
    2. Each metrics will be tagged with either a "domainId" or "sponsorId"
   */
  def recordActivity(windowRule: ActivityWindowRule, activity: AgentActivity): Unit = {
    logger.info(s"track activity: $activity, window: $windowRule, tags: ${agentTags(windowRule, activity.domainId)}")
    metricsWriter.gaugeIncrement(windowRule.activityType.metricName, tags = agentTags(windowRule, activity.domainId))
    val event = AgentActivityRecorded(
      activity.domainId,
      activity.timestamp,
      state.sponsorRel,
      activity.activityType,
      activity.relId.getOrElse(""),
      state.key(windowRule, activity.id(windowRule.activityType)),
    )

    writeAndApply(event)
  }

  private def agentTags(windowRule: ActivityWindowRule, domainId: DomainId): Map[String, String] =
    windowRule.activityType match {
      case ActiveUsers =>
        ActiveUsers.tags(
          state.sponsorRel.getOrElse(SponsorRel.empty).sponsorId,
          windowRule.frequencyType
        )
      case ActiveRelationships =>
        ActiveRelationships.tags(
          domainId,
          windowRule.frequencyType,
          state.sponsorRel.getOrElse(SponsorRel.empty).sponseeId
        )
    }

  /**
   * actor persistent state object
   */
  class State(_activities: Map[StateKey, AgentActivity] = Map.empty,
              _activityWindow: ActivityWindow = ActivityWindow(Set()),
              _sponsorRel: Option[SponsorRel] = None,
              _attemptedSponsorRetrieval: Boolean = false) {

    def copy(activities: Map[StateKey, AgentActivity]=_activities,
             activityWindow: ActivityWindow=_activityWindow,
             sponsorRel: Option[SponsorRel]=None,
             attemptedSponsorRetrieval: Boolean=_attemptedSponsorRetrieval): State =
      new State(activities, activityWindow, sponsorRel, attemptedSponsorRetrieval)

    def sponsorRel: Option[SponsorRel] = _sponsorRel
    def withSponsorRel(sponsorRel: SponsorRel): State =
      copy(sponsorRel=Some(sponsorRel))
    /**
     * A sponsor can be undefined (If activity occurs and there is no sponsor)
     */
    def sponsorReady(): Boolean = sponsorRel.isDefined || _attemptedSponsorRetrieval
    def withAttemptedSponsorRetrieval(): State =
      copy(attemptedSponsorRetrieval=true)

    def activityWindows: ActivityWindow = _activityWindow
    def withActivityWindow(activityWindow: ActivityWindow): State = copy(activityWindow=activityWindow)

    def activity(window: ActivityWindowRule, id: Option[String]): Option[AgentActivity] = _activities.get(key(window, id))
    def withAgentActivity(key: StateKey, activity: AgentActivity): State =
      copy(activities=_activities + (key -> activity))

    def key(window: ActivityWindowRule, id: Option[String]=None): StateKey =
      s"${window.activityType.metricName}-${window.frequencyType.toString}-${id.getOrElse("")}"
  }

  override def futureExecutionContext: ExecutionContext = executionContext

  type StateKey = String
  type StateType = State
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

/** How often a behavior is recorded
 *  CalendarMonth: January, February, ..., December
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
trait ActivityTracking extends ActorMessage
final case class ActivityWindow(windows: Set[ActivityWindowRule]) extends ActivityTracking

final case class AgentActivity(domainId: DidStr,
                               timestamp: IsoDateTime,
                               activityType: String,
                               relId: Option[String]=None) extends ActivityTracking {
  def id(behavior: ActivityType): Option[String] =
    behavior match {
      case ActiveUsers          => Some(domainId)
      case ActiveRelationships  => relId
      case _ => None
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

final case class ActivityWindowRule(frequencyType: FrequencyType, activityType: ActivityType)

/** ActivityTracker Event Base Type */
trait Active extends ActorMessage

