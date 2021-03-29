package com.evernym.verity.actor.appStateManager.state

import com.evernym.verity.actor.appStateManager.{AppStateManagerBase, DrainingStarted, EventParam, MildSystemError, Recovered, RecoveredFromCause, SeriousSystemError, Shutdown}
import com.evernym.verity.actor.appStateManager.AppStateConstants._

object DrainingState extends AppState {

  override val name: String = STATUS_DRAINING

  /**
   * When app state is 'DrainingState', it handles below events apart from
   * what is handled in 'commonEventHandler' in AppState
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def handleEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    param.event match {
      case RecoveredFromCause | Recovered | MildSystemError | SeriousSystemError
                                => reportAndStay(param)
      case DrainingStarted      => logger.info(s"received $DrainingStarted while draining is already in progress")
      case Shutdown             => performTransition(ShutdownState, param)
      case x                    => throwEventNotSupported(x)
    }
  }

  /**
   * This function gets executed when app state transitions from any other state
   * to this state (DrainingState)
   *
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  override def postTransition(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    //TODO: Doing it the current way ensures traffic stops being routed from the load balancer to this node,
    // but does not ensure the akka node gracefully leaves the cluster (migrate singletons, shards, etc.)
    notifierService.setStatus(name)
    appStateManager.performServiceDrain()
  }
}
