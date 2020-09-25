package com.evernym.verity.apphealth.state

import com.evernym.verity.Exceptions.TransitionHandlerNotProvidedException
import com.evernym.verity.apphealth.AppStateConstants.STATUS_LISTENING
import com.evernym.verity.apphealth.{ActionHandler, AppStateManagerBase, EventParam, ManualUpdate, Recovered}

trait AppState {

  /**
   * state name
   */
  def name: String

  /**
   * each app state needs to implement this function to determine
   * what it should do based on the received event
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  protected def handleEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit

  /**
   * logic which will get executed whenever this state comes into effect
   * @param param event parameter
   * @param appStateManager app state manager base instance
   */
  protected def postTransition(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit

  private def commonEventHandler(implicit appStateManager: AppStateManagerBase): PartialFunction[EventParam, Unit] = {
    case ep @ EventParam(ManualUpdate(STATUS_LISTENING), _, _, _, _)
      if Set(SickState, DegradedState).contains(appStateManager.getCurrentState) =>
      appStateManager.performTransition(ListeningState, ep)

    case ep @ EventParam(Recovered, _, _, _, _) =>
      appStateManager.performTransition(ListeningState, ep)
  }

  def processEvent(param: EventParam)(implicit appStateManager: AppStateManagerBase): Unit = {
    val commonEventProcessor = commonEventHandler(appStateManager)
    if (commonEventProcessor.isDefinedAt(param)) {
      commonEventProcessor(param)
    } else {
      handleEvent(param)
    }
    appStateManager.getCurrentState.postTransition(param)
  }

  def performAction(actionHandler: Option[ActionHandler] = None): Any = {
    actionHandler.foreach { ah =>
      ah.actionCmdHandler.apply(ah.cmd)
    }
  }

  protected def performServiceShutdown()(implicit appStateManager: AppStateManagerBase): Unit = {
    import appStateManager._
    util.performSystemExit()
  }

  def throwEventNotSupported(event: Any): Unit = {
    throw new TransitionHandlerNotProvidedException(Option(s"$name state not handling event: $event"))
  }

  override def toString: String = name
}
