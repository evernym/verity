package com.evernym.verity.actor.metrics

import akka.actor.Props
import akka.event.LoggingReceive
import com.evernym.verity.actor.agent.{SponsorRel, RecordingAgentActivity}
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.{ActorMessageClass, WindowActivityDefined, WindowRules}
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.CustomMetrics.{AS_ACTIVE_USER_AGENT_COUNT, AS_USER_AGENT_ACTIVE_RELATIONSHIPS}
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.util.TimeUtil.{IsoDateTime, dateAfterDuration, isDateExpired, toMonth}
import com.typesafe.scalalogging.Logger

import scala.concurrent.duration.Duration

/**
 Records an Agent's
  1. activity within a specified window
  2. active relationships within a specified window
 */
class ActivityTracker(override val appConfig: AppConfig)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {
  type StateKey = String
  type StateType = State
  var state = new State
  val logger: Logger = getLoggerByClass(classOf[ActivityTracker])
 /**
  * actor persistent state object
  */
 class State(_activity: Map[StateKey, AgentActivity]=Map.empty,
             _activityWindow: ActivityWindow=ActivityWindow(Set()),
             _sponsorRel: Option[SponsorRel]=None) {

   def copy(activity: Map[StateKey, AgentActivity]=_activity,
            activityWindow: ActivityWindow=_activityWindow,
            sponsorRel: Option[SponsorRel]=None): State = new State(activity, activityWindow, sponsorRel)

   def sponsorRel: Option[SponsorRel] = _sponsorRel
   def withSponsorRel(sponsorRel: SponsorRel): State =
     copy(sponsorRel=Some(sponsorRel))

   def activityWindows: ActivityWindow = _activityWindow
   def withActivityWindow(activityWindow: ActivityWindow): State = copy(activityWindow=activityWindow)

   def activity(window: ActiveWindowRules, id: Option[String]): Option[AgentActivity] = _activity.get(key(window, id))
   def withAgentActivity(key: StateKey, activity: AgentActivity): State =
     copy(activity=_activity + (key -> activity))

   def key(window: ActiveWindowRules, id: Option[String]=None): StateKey =
     s"${window.activityType.metricBase}-${window.activityFrequency.toString}-${id.getOrElse("")}"

 }

  override def beforeStart(): Unit = {
    super.beforeStart()
    applyEvent(ConfigUtil.findActivityWindow(appConfig).asEvt)
    logger.info(s"started activity tracker with windows: ${state.activityWindows}")
  }

 /**
  * internal command handler
  */
 val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
  case activity: AgentActivity => handleAgentActivity(activity)
  case updateActivityWindows: ActivityWindow => applyEvent(updateActivityWindows.asEvt)
//  case SetSponsorRel(rel) =>
 }

 val receiveEvent: Receive = {
  case r: RecordingAgentActivity =>
    state = state.withAgentActivity(r.stateKey, AgentActivity(r))
  case w: WindowActivityDefined =>
    state = state.withActivityWindow(ActivityWindow.fromEvt(w))
 }

  /**
   * 1. Process each defined window
   * 2. Check if new activity within window
   * 3. check activity type (AU, AR)
   * 4. Record accordingly
   */
  def handleAgentActivity(activity: AgentActivity): Unit = {
   logger.debug(s"request to record activity: $activity, windows: ${state.activityWindows}")
   state.activityWindows
     .windows
     .filter(window => isUntrackedMetric(window, activity))
     .filter(window => relationshipValidation(window, activity))
     .foreach(window => recordAgentMetric(window, activity))
 }

  def isUntrackedMetric(window: ActiveWindowRules, newActivity: AgentActivity): Boolean = {
    val lastActivity = state.activity(window, newActivity.id(window.activityType))
    lastActivity.forall(activity => {
      window.activityFrequency match {
        case CalendarMonth =>
          //If timestamps are not in the same month, record new activity
          !toMonth(activity.timestamp).equals(toMonth(newActivity.timestamp))
        case VariableDuration(duration) =>
          //If newActivity is >= then the expiredDate, record new activity
          val expiredDate = dateAfterDuration(activity.timestamp, duration)
          isDateExpired(newActivity.timestamp, expiredDate)
      }
    })
  }

  def relationshipValidation(window: ActiveWindowRules, activity: AgentActivity): Boolean =
    window.activityType match {
      case ActiveRelationships if activity.relId.isEmpty => false
      case _ => true
    }

  /*
    1. There will be two metrics, active users, active relationships
    2. Each entry will be tagged with either a domainId or sponsorId
    3. The entry will only be written if it hasn't already been written in that timeframe
      // Means I have to change how I'm keeping track in the state
    4.
   */
  def recordAgentMetric(window: ActiveWindowRules, activity: AgentActivity): Unit = {
    logger.info(s"track activity: $activity, window: $window, tags: ${agentTags(window, activity)}")
    MetricsWriter.gaugeApi.incrementWithTags(window.activityType.metricBase, agentTags(window, activity))
    val recording = RecordingAgentActivity(
      activity.domainId,
      activity.timestamp,
      activity.sponsorRel,
      activity.activityType,
      activity.relId.getOrElse(""),
      state.key(window, activity.id(window.activityType)),
    )

    writeAndApply(recording)
  }

  private def agentTags(behavior: ActiveWindowRules, agentActivity: AgentActivity): Map[String, String] =
    behavior.activityType match {
      case ActiveUsers =>
        ActiveUsers.tags(
          agentActivity.sponsorRel.getOrElse(SponsorRel.empty).sponsorId,
          behavior.activityFrequency
        )
      case ActiveRelationships =>
        ActiveRelationships.tags(
          agentActivity.domainId,
          behavior.activityFrequency,
          agentActivity.sponsorRel.getOrElse(SponsorRel.empty).sponseeId
        )
    }
}

object ActivityTracker {
 def props(implicit config: AppConfig): Props = {
  Props(new ActivityTracker(config))
 }
}

/** Types of agent activity that may be used for metrics
 *  ActiveUsers: Manages number of active users within a defined time window
 *  ActiveRelationships: Manages number of relationships a specific Agent (domainId) has within a defined window
 * */
trait Behavior {
  def metricBase: String
  def idType: String
  def tags(id: String, frequency: FrequencyType): Map[String, String] =
    Map(
      idType -> id,
      "frequency" -> frequency.toString
    )
}
trait AgentBehavior extends Behavior
case object ActiveUsers extends AgentBehavior {
  def metricBase: String = AS_ACTIVE_USER_AGENT_COUNT
  def idType: String = "sponsorId"
}
case object ActiveRelationships extends AgentBehavior {
  def metricBase: String = AS_USER_AGENT_ACTIVE_RELATIONSHIPS
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
  override def toString: String = "monthly"
}
case class VariableDuration(duration: Duration) extends FrequencyType {
  override def toString: String = duration.toString
}
object VariableDuration {
  def apply(duration: String): VariableDuration = new VariableDuration(Duration(duration))
}

/** ActivityTracker Commands */
trait ActivityTracking extends ActorMessageClass
final case class ActivityWindow(windows: Set[ActiveWindowRules]) extends ActivityTracking {
  def asEvt: WindowActivityDefined =
    WindowActivityDefined(windows.map(x => WindowRules(x.activityFrequency.toString, x.activityType.toString)).toSeq)
}
object ActivityWindow {
  def fromEvt(e: WindowActivityDefined): ActivityWindow = new ActivityWindow(e.windows.map(x => {
    val frequency: FrequencyType = x.frequency match {
      case f if f == CalendarMonth.toString => CalendarMonth
      case f => VariableDuration(f)
    }

    val behavior: Behavior = x.behavior match {
      case b if b == ActiveUsers.toString => ActiveUsers
      case b if b == ActiveRelationships.toString => ActiveRelationships
    }
    ActiveWindowRules(frequency, behavior)
  }).toSet)

}
final case class AgentActivity(domainId: DID,
                               timestamp: IsoDateTime,
                               sponsorRel: Option[SponsorRel]=None,
                               activityType: String,
                               relId: Option[String]=None) extends ActivityTracking {
  def id(behavior: Behavior): Option[String] =
    behavior match {
      case ActiveUsers => Some(domainId)
      case ActiveRelationships => relId
      case _ => None
    }
}

object AgentActivity {
  def apply(r: RecordingAgentActivity): AgentActivity =
    new AgentActivity(
      r.domainId,
      r.timestamp,
      r.sponsorRel,
      r.activityType,
      if (r.relId == "") None else Some(r.relId)
    )
}

case class SetSponsorRel(sponsor: Option[SponsorRel]) extends ActivityTracking

final case class ActiveWindowRules(activityFrequency: FrequencyType, activityType: Behavior)

/** ActivityTracker Event Base Type */
trait Active extends ActorMessageClass

