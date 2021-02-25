package com.evernym.verity.actor.appStateManager.state

import com.evernym.verity.actor.appStateManager.{AppStateManagerBase, DrainingStarted, EventParam, MildSystemError, RecoveredFromCause, SeriousSystemError}
import com.evernym.verity.actor.appStateManager.AppStateConstants._

object DegradedState extends AppState {

  override val name: String = STATUS_DEGRADED

  /**
   * When app state is 'DegradedState', it handles below events apart from
   * what is handled in 'commonEventHandler' in AppState
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def handleEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    param.event match {
      case RecoveredFromCause   => reportAndStay(param)
      case MildSystemError      => reportAndStay(param)
      case SeriousSystemError   => performTransition(SickState, param)
      case DrainingStarted      => performTransition(DrainingState, param)
      case x                    => throwEventNotSupported(x)
    }
  }

  /**
   * This function gets executed when app state transitions from any other state
   * to this state (DegradedState)
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def postTransition(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    notifierService.setStatus(name)
  }

}
