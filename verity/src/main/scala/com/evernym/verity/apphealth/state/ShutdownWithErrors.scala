package com.evernym.verity.apphealth.state

import com.evernym.verity.apphealth.AppStateConstants.STATUS_SHUTDOWN_WITH_ERRORS
import com.evernym.verity.apphealth.{AppStateManagerBase, EventParam}

object ShutdownWithErrors extends AppState {

  override val name: String = STATUS_SHUTDOWN_WITH_ERRORS

  /**
   * When app state is 'ShutdownWithErrors', it handles below events apart from
   * what is handled in 'commonEventHandler' in AppState
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def handleEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    param.event match {
      case x => logger.info(s"received $x while shut down is already in progress")
    }
  }

  /**
   * This function gets executed when app state transitions from any other state
   * to this state (ShutdownWithErrors)
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def postTransition(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    performAction(param.actionHandler)
    sysServiceNotifier.setStatus(name)

    sysServiceNotifier.stop()
    performServiceShutdown()
  }
}