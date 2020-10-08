package com.evernym.verity.actor.metrics

import akka.actor.Props
import akka.event.LoggingReceive
import com.evernym.verity.actor.agent.agency.SponsorRel
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.{ActorMessageClass, RecordingAgentActivity}
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.metrics.CustomMetrics.{AS_ACTIVE_USER_AGENT_COUNT, AS_USER_AGENT_ACTIVE_RELATIONSHIPS}
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.util.TimeUtil.{IsoDateTime, dateAfterDuration, isDateExpired, toMonth}

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
  var activityWindows: ActivityWindow = ActivityWindow(Set())

 /**
  * actor persistent state object
  */
 class State(_activity: Map[StateKey, AgentActivity]=Map.empty) {

   def key(window: ActiveWindowRules, id: Option[String]=None): StateKey =
     s"${window.activityType.metricBase}-${window.activityFrequency.toString}-${id.getOrElse("")}"
   def copy(newActivity: Map[StateKey, AgentActivity]): State = new State(newActivity)
   def activity: Map[StateKey, AgentActivity] = _activity
   def activity(window: ActiveWindowRules, id: Option[String]): Option[AgentActivity] =
     _activity.get(key(window, id))
 }

  override def beforeStart(): Unit = {
    super.beforeStart()
    activityWindows = ConfigUtil.findActivityWindow(appConfig)
  }

 /**
  * internal command handler
  */
 val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
  case activity: AgentActivity => handleAgentActivity(activity)
  case updateActivityWindows: ActivityWindow => activityWindows = updateActivityWindows
 }

 val receiveEvent: Receive = {
  case r: RecordingAgentActivity =>
    state = state.copy( state.activity + (r.stateKey -> AgentActivity(r)))
 }

  /**
   * 1. Process each defined window
   * 2. Check if new activity within window
   * 3. check activity type (AU, AR)
   * 4. Record accordingly
   */
  def handleAgentActivity(activity: AgentActivity): Unit = {
   appConfig.logger.debug(s"request to record activity: $activity, windows: $activityWindows")
   activityWindows
     .windows
     .filter(window => isUntrackedMetric(window, activity))
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

  /*
    1. There will be two metrics, active users, active relationships
    2. Each entry will be tagged with either a domainId or sponsorId
    3. The entry will only be written if it hasn't already been written in that timeframe
      // Means I have to change how I'm keeping track in the state
    4.
   */
  def recordAgentMetric(window: ActiveWindowRules, activity: AgentActivity): Unit = {
    appConfig.logger.info(s"track activity: $activity, window: $window, tags: ${agentTags(window, activity)}")
    MetricsWriter.gaugeApi.incrementWithTags(window.activityType.metricBase, agentTags(window, activity))
    val recording = RecordingAgentActivity(
      activity.domainId,
      activity.timestamp,
      activity.sponsorRel.sponsorId,
      activity.sponsorRel.sponseeId,
      activity.activityType,
      activity.relId.getOrElse(""),
      state.key(window, activity.id(window.activityType))
    )

    writeAndApply(recording)
  }

  private def agentTags(behavior: ActiveWindowRules, agentActivity: AgentActivity): Map[String, String] =
    behavior.activityType match {
      case ActiveUsers => ActiveUsers.tags(agentActivity.sponsorRel.sponsorId, behavior.activityFrequency)
      case ActiveRelationships => ActiveRelationships.tags(agentActivity.domainId, behavior.activityFrequency)
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
final case class ActivityWindow(windows: Set[ActiveWindowRules]) extends ActivityTracking
final case class AgentActivity(domainId: DID,
                               timestamp: IsoDateTime,
                               sponsorRel: SponsorRel,
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
      SponsorRel(r.sponsorId, r.sponseeId),
      r.activityType,
      if (r.relId == "") None else Some(r.relId)
    )
}

final case class ActiveWindowRules(activityFrequency: FrequencyType, activityType: Behavior)

/** ActivityTracker Event Base Type */
trait Active extends ActorMessageClass

