package com.evernym.verity.actor.metrics

import akka.actor.Props
import akka.event.LoggingReceive
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.actor.{ActorMessageClass, RecordingAgentActivity}
import com.evernym.verity.config.AppConfig
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
class ActivityTracker(override val appConfig: AppConfig, var activityWindows: ActivityWindow)
  extends BasePersistentActor
    with DefaultPersistenceEncryption {
 type MetricKey = String
 type StateType = State
 var state = new State

 /**
  * actor persistent state object
  */
 class State(_activity: Map[MetricKey, AgentActivity]=Map.empty) {
   def copy(newActivity: Map[MetricKey, AgentActivity]): State = new State(newActivity)
   def activity: Map[MetricKey, AgentActivity] = _activity
   def activity(key: MetricKey): Option[AgentActivity] = _activity.get(key)
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
    state = state.copy(
      state.activity + (r.metricKey -> AgentActivity(r.domainId, r.timestamp, r.sponsorId, r.activityType))
    )
 }

 def handleAgentActivity(activity: AgentActivity): Unit =
   /**
    * 1. Process each defined window
    * 2. Check if new activity within window
    * 3. check activity type (AU, AR)
    * 4. Record accordingly
    */
   activityWindows
     .windows
     .filter(window => isUntrackedMetric(state.activity(window.metricKey), activity.timestamp, window.activityFrequency))
     .foreach(window => recordAgentMetric(window, activity))

  def isUntrackedMetric(lastActivity: Option[AgentActivity], newActivity: IsoDateTime, frequency: FrequencyType): Boolean = {
    lastActivity.forall(activity => {
      frequency match {
        case CalendarMonth =>
          //If timestamps are not in the same month, record new activity
          !toMonth(activity.timestamp).equals(toMonth(newActivity))
        case VariableDuration(duration) =>
          //If newActivity is >= then the expiredDate, record new activity
          val expiredDate = dateAfterDuration(activity.timestamp, duration)
          isDateExpired(newActivity, expiredDate)
      }
    })
  }

  def recordAgentMetric(window: ActiveWindowRules, activity: AgentActivity): Unit = {
    MetricsWriter.gaugeApi.incrementWithTags(window.metricKey, agentTags(window, activity))
    val recording = RecordingAgentActivity(
      activity.domainId,
      activity.timestamp,
      activity.sponsorId,
      activity.activityType,
      window.metricKey
    )

    writeAndApply(recording)
  }

  private def agentTags(behavior: ActiveWindowRules, agentActivity: AgentActivity): Map[String, String] =
    behavior.activityType match {
      case ActiveUsers => ActiveUsers.tags(agentActivity.sponsorId)
      case ActiveRelationships => ActiveRelationships.tags(agentActivity.domainId)
    }
}

object ActivityTracker {
 def props(implicit config: AppConfig, windows: ActivityWindow): Props = {
  Props(new ActivityTracker(config, windows))
 }
}

/** Types of agent activity that may be used for metrics
 *  ActiveUsers: Manages number of active users within a defined time window
 *  ActiveRelationships: Manages number of relationships a specific Agent (domainId) has within a defined window
 * */
trait Behavior {
  def metricBase: String
  def tagType: String
  def tags(value: String): Map[String, String] = Map(tagType -> value)
}
case object ActiveUsers extends Behavior {
  def metricBase: String = AS_ACTIVE_USER_AGENT_COUNT
  def tagType: String = "sponsorId"
}
case object ActiveRelationships extends Behavior {
  def metricBase: String = AS_USER_AGENT_ACTIVE_RELATIONSHIPS
  def tagType: String = "domainId"
}

/** How often a behavior is recorded
 *  CalendarMonth: January, February, ..., December
 *  VariableDuration: Any datetime range
 * */
trait FrequencyType
case object CalendarMonth extends FrequencyType {
  override def toString: String = "Monthly"
}
case class VariableDuration(duration: Duration) extends FrequencyType {
  override def toString: String = duration.toString
}

/** ActivityTracker Commands */
trait ActivityTracking extends ActorMessageClass
final case class ActivityWindow(windows: Set[ActiveWindowRules]) extends ActivityTracking
final case class AgentActivity(domainId: DID, timestamp: IsoDateTime, sponsorId: String, activityType: String) extends ActivityTracking

final case class ActiveWindowRules(activityFrequency: FrequencyType, activityType: Behavior) {
  def metricKey: String = s"${activityType.metricBase}.${activityType.toString}.${activityFrequency.toString}"
}

/** ActivityTracker Event Base Type */
trait Active extends ActorMessageClass

