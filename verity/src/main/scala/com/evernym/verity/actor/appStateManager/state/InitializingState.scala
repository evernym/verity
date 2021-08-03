package com.evernym.verity.actor.appStateManager.state

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import com.evernym.verity.util2.Exceptions.TransitionHandlerNotProvidedException
import com.evernym.verity.actor.appStateManager.{AppStateManagerBase, DrainingStarted, ErrorOccurrences, EventParam, ListeningSuccessful, MildSystemError, RecoveredFromCause, SeriousSystemError}
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.config.ConfigConstants.{APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_COUNT, APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_DURATION}
import com.evernym.verity.config.AppConfigWrapper


class InitializingState extends AppState {

  override val name: String = STATUS_INITIALIZING

  var errorOccurrencesByCauseCode: Map[String, ErrorOccurrences] = Map.empty
  lazy val maxRetryCount = AppConfigWrapper.getIntOption(APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_COUNT).getOrElse(10)
  lazy val maxRetryDuration = AppConfigWrapper.getIntOption(APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_DURATION).getOrElse(240)

  /**
   * When app state is 'InitializingState', it handles below events apart from
   * what is handled in 'commonEventHandler' in AppState
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def handleEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    param.event match {
      case ListeningSuccessful  => performTransition(ListeningState, param)
      case MildSystemError      => performTransition(DegradedState, param)
      case SeriousSystemError   => performTransitionIfNeeded(ShutdownWithErrors, param)
      case RecoveredFromCause   => removeRecoveredError(param.causeDetail.code)
      case DrainingStarted      => performTransition(DrainingState, param)
      case x                    => throw new TransitionHandlerNotProvidedException(Option(s"Initializing state not handling event: $x"))
    }
  }

  /**
   * This function gets executed when app state transitions from any other state
   * to this state (InitializingState)
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def postTransition(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    //nothing to do
  }

  //track error occurrences and perform transition if needed
  def performTransitionIfNeeded(newState: AppState, param: EventParam)
                               (implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    trackErrorOccurrences(param.causeDetail.code)
    newState match {
      case ShutdownWithErrors if retriesExhausted(param.causeDetail.code) => performTransition(newState, param)
      case ShutdownWithErrors => //nothing to do
    }
  }

  def trackErrorOccurrences(causeCode: String): Unit = {
    val updatedDetail = errorOccurrencesByCauseCode.get(causeCode).map { eo =>
      eo.copy(lastObservedAt = LocalDateTime.now, total = eo.total + 1)
    }.getOrElse(ErrorOccurrences(LocalDateTime.now, LocalDateTime.now, 1))
    errorOccurrencesByCauseCode += (causeCode -> updatedDetail)
  }

  def retriesExhausted(causeCode: String): Boolean = {
    errorOccurrencesByCauseCode.get(causeCode).exists { eo =>
      val durationInSeconds = ChronoUnit.SECONDS.between(eo.firstObservedAt, eo.lastObservedAt)
      eo.total >= maxRetryCount || durationInSeconds >= maxRetryDuration
    }
  }

  def removeRecoveredError(causeCode: String): Unit = {
    errorOccurrencesByCauseCode = errorOccurrencesByCauseCode.filterNot(_._1 == causeCode)
  }
}
