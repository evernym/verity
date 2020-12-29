package com.evernym.verity

import java.time.{LocalDateTime, ZonedDateTime}

import akka.actor.ActorSystem
import com.evernym.verity.apphealth.state.AppState

package object apphealth {

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
    val CONTEXT_LEDGER_OPERATION = "ledger-operation"
    val CONTEXT_SMS_OPERATION = "sms-operation"
    val CONTEXT_PUSH_NOTIF = "push-notif"
    val CONTEXT_MANUAL_UPDATE = "manual-update"

    val AUTO_RECOVERED_CODE = "auto-recovered"

    val MSG_AGENT_SERVICE_INIT_STARTED = "agent-service-init-started"

  }


  trait Event

  trait ErrorEvent extends Event

  import AppStateConstants._

  object ListeningSuccessful extends Event {
    override def toString: String = EVENT_LISTENING_STARTED
  }

  object SeriousSystemError extends ErrorEvent {
    override def toString: String = EVENT_SERIOUS_SYS_ERROR
  }

  object MildSystemError extends ErrorEvent {
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

  case class ErrorEventParam(event: Event,
                             context: String,
                             cause: Throwable,
                             msg: Option[String] = None,
                             actionHandler: Option[ActionHandler] = None,
                             system: Option[ActorSystem] = None)

  case class SuccessEventParam(event: Event,
                               context: String,
                               causeDetail: CauseDetail,
                               msg: Option[String] = None,
                               actionHandler: Option[ActionHandler] = None,
                               system: Option[ActorSystem] = None)

  case class EventParam(event: Event,
                        context: String,
                        causeDetail: CauseDetail,
                        actionHandler: Option[ActionHandler] = None,
                        system: Option[ActorSystem] = None) {
    def getCauseMsg: String = causeDetail.msg
  }

  case class EventDetail(date: ZonedDateTime, state: AppState, cause: String)

  case class ErrorOccurrences(firstObservedAt: LocalDateTime, lastObservedAt: LocalDateTime, total: Int)

}