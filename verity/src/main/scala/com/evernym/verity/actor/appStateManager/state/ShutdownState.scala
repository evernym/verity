package com.evernym.verity.actor.appStateManager.state

import com.evernym.verity.actor.appStateManager.{AppStateManagerBase, EventParam}
import com.evernym.verity.actor.appStateManager.AppStateConstants._

object ShutdownState extends AppState {

  override val name: String = STATUS_SHUTDOWN

  /**
   * When app state is 'ShutdownState', it handles below events apart from
   * what is handled in 'commonEventHandler' in AppState
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def handleEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    param.event match {
      case x => logger.info(s"received $x while graceful shut down is already in progress")
    }
  }

  /**
   * This function gets executed when app state transitions from any other state
   * to this state (ShutdownState)
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def postTransition(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    notifierService.setStatus(name)

    notifierService.stop()
    performServiceShutdown()
  }
}