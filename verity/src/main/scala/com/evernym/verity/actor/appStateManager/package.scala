package com.evernym.verity.actor

import java.time.{LocalDateTime, ZonedDateTime}

import com.evernym.verity.actor.appStateManager.state.AppState

package object appStateManager {

  object AppStateConstants {

    val STATUS_INITIALIZING = "Initializing"
    val STATUS_LISTENING = "Listening"
    val STATUS_DEGRADED = "Degraded"
    val STATUS_SICK = "Sick"
    val STATUS_DRAINING = "Draining"
    val STATUS_SHUTDOWN_WITH_ERRORS = "ShutdownWithErrors"
    val STATUS_SHUTDOWN = "Shutdown"

    val EVENT_LISTENING_STARTED = "ListeningStarted"
    val EVENT_SERIOUS_SYS_ERROR = "SeriousSystemError"
    val EVENT_MILD_SYS_ERROR = "MildSystemError"
    val EVENT_RECOVERED_FROM_ERROR = "RecoveredFromAnError"
    val EVENT_RECOVERED_FROM_ALL_ERRORS = "RecoveredFromAllErrors"
    val EVENT_DRAINING_STARTED = "DrainingStarted"
    val EVENT_SHUTTING_DOWN_STARTED = "ShuttingDownStarted"
    val EVENT_MANUAL_UPDATE = "ManualUpdate"
    val EVENT_SHUTDOWN = "Shutdown"

    val CONTEXT_GENERAL = "general"
    val CONTEXT_AGENT_SERVICE_INIT = "agent-service-init"
    val CONTEXT_AGENT_SERVICE_DRAIN = "agent-service-drain"
    val CONTEXT_AGENT_SERVICE_SHUTDOWN = "agent-service-shutdown"
    val CONTEXT_EVENT_PERSIST = "event-persist"
    val CONTEXT_EVENT_DELETION = "event-deletion"
    val CONTEXT_EVENT_RECOVERY = "event-recovery"
    val CONTEXT_EVENT_TRANSFORMATION_UNDO = "event-transformation-undo"
    val CONTEXT_ACTOR_INIT = "actor-init"
    val CONTEXT_CONFIG_LOADING = "config-loading"
    val CONTEXT_LIB_INDY_INIT = "lib-indy-init"
    val CONTEXT_LIB_MYSQLSTORAGE_INIT = "lib-mysqlstorage-init"
    val CONTEXT_LEDGER_OPERATION = "ledger-operation"
    val CONTEXT_SMS_OPERATION = "sms-operation"
    val CONTEXT_PUSH_NOTIF = "push-notif"
    val CONTEXT_MANUAL_UPDATE = "manual-update"

    val AUTO_RECOVERED_CODE = "auto-recovered"

    val MSG_AGENT_SERVICE_INIT_STARTED = "agent-service-init-started"

  }


  trait Event

  trait ErrorEventBase extends Event

  import AppStateConstants._

  object ListeningSuccessful extends Event {
    override def toString: String = EVENT_LISTENING_STARTED
  }

  object SeriousSystemError extends ErrorEventBase {
    override def toString: String = EVENT_SERIOUS_SYS_ERROR
  }

  object MildSystemError extends ErrorEventBase {
    override def toString: String = EVENT_MILD_SYS_ERROR
  }

  object RecoveredFromCause extends Event {
    override def toString: String = EVENT_RECOVERED_FROM_ERROR
  }

  object Recovered extends Event {
    override def toString: String = EVENT_RECOVERED_FROM_ALL_ERRORS
  }

  object DrainingStarted extends Event {
    override def toString: String = EVENT_DRAINING_STARTED
  }

  object Shutdown extends Event {
    override def toString: String = EVENT_SHUTDOWN
  }

  case class ManualUpdate(newStatus: String) extends Event {
    override def toString: String = EVENT_MANUAL_UPDATE
  }

  case class CauseDetail(code: String, msg: String)

  case class ErrorEvent(event: Event,
                        context: String,
                        cause: Throwable,
                        msg: Option[String] = None) extends AppStateEvent

  case class SuccessEvent(event: Event,
                          context: String,
                          causeDetail: CauseDetail,
                          msg: Option[String] = None) extends AppStateEvent

  case class RecoverIfNeeded(context: String) extends AppStateEvent

  case object StartDraining extends AppStateEvent


  case object GetHeartbeat extends AppStateRequest

  case object GetEvents extends AppStateRequest

  case object GetCurrentState extends AppStateRequest

  case object GetDetailedAppState extends AppStateRequest

  case class EventParam(event: Event,
                        context: String,
                        causeDetail: CauseDetail) {
    def getCauseMsg: String = causeDetail.msg
  }

  case class EventDetail(date: ZonedDateTime, state: AppState, cause: String) {
    def toEventDetailResp: EventDetailResp = EventDetailResp(date, state.toString, cause)
  }

  case class ErrorOccurrences(firstObservedAt: LocalDateTime, lastObservedAt: LocalDateTime, total: Int)

}