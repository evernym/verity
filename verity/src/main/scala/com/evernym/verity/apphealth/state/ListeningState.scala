package com.evernym.verity.apphealth.state

import com.evernym.verity.apphealth.AppStateConstants.STATUS_LISTENING
import com.evernym.verity.apphealth.{AppStateManagerBase, DrainingStarted, EventParam, MildSystemError, SeriousSystemError}

object ListeningState extends AppState {

  override val name: String = STATUS_LISTENING

  /**
   * When app state is 'ListeningState', it handles below events apart from
   * what is handled in 'commonEventHandler' in AppState
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def handleEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    param.event match {
      case MildSystemError    => performTransition(DegradedState, param)
      case SeriousSystemError => performTransition(SickState, param)
      case DrainingStarted    => performTransition(DrainingState, param)
      case x                  => throwEventNotSupported(x)
    }
  }

  /**
   * This function gets executed when app state transitions from any other state
   * to this state (ListeningState)
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def postTransition(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    performAction(param.actionHandler)
    sysServiceNotifier.started()
    sysServiceNotifier.setStatus(name)
  }
}