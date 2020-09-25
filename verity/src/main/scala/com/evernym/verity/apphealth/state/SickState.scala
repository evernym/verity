package com.evernym.verity.apphealth.state

import com.evernym.verity.apphealth.AppStateConstants.STATUS_SICK
import com.evernym.verity.apphealth.{AppStateManagerBase, DrainingStarted, EventParam, RecoveredFromCause, SeriousSystemError}

object SickState extends AppState {

  override val name: String = STATUS_SICK

  /**
   * When app state is 'SickState', it handles below events apart from
   * what is handled in 'commonEventHandler' in AppState
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def handleEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    param.event match {
      case RecoveredFromCause   => reportAndStay(param)
      case SeriousSystemError   => reportAndStay(param)
      case DrainingStarted      => performTransition(DrainingState, param)
      case x                    => throwEventNotSupported(x)
    }
  }

  /**
   * This function gets executed when app state transitions from any other state
   * to this state (SickState)
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def postTransition(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    performAction(param.actionHandler)
    sysServiceNotifier.setStatus(name)
  }
}