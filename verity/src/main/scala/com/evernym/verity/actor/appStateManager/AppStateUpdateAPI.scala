package com.evernym.verity.actor.appStateManager

import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import com.evernym.verity.logging.LoggingUtil
import com.typesafe.scalalogging.Logger

/**
 * This is still a global "object with state" (temporarily)
 * and it should be removed once we do some more long term design refactoring
 * around app state manager around how it should handle failure and do auto recovery
 */
class AppStateUpdateAPIImpl(val as: ActorSystem) extends Extension {

  //TODO: how to make sure this extension is thread safe?

  //this is to make sure code doesn't keep publishing "RecoverIfNeeded" event
  // even if the app state is already recovered
  var failedContexts = Set.empty[String]

  def publishEvent(event: AppStateEvent): Unit = {
    val shallPublishEvent = event match {
      case ErrorEvent(_, context, _, _) if ! failedContexts.contains(context) =>
        failedContexts += context
        true
      case RecoverIfNeeded(context) if failedContexts.contains(context) =>
        failedContexts -= context
        true
      case RecoverIfNeeded(context) if ! failedContexts.contains(context) =>
        false
      case _  => true
    }
    if (shallPublishEvent) {
      as.eventStream.publish(event)
    }
  }
}

object AppStateUpdateAPI extends ExtensionId[AppStateUpdateAPIImpl] with ExtensionIdProvider{

  val appStateUpdateLogger: Logger = LoggingUtil.getLoggerByName("AppStateUpdateAPI")

  /**
   * this is called from those places which doesn't have access to actor system
   * and hence can't publish the event to the 'event bus'
   * @param event
   */
  def handleError(event: ErrorEvent): Unit = {
    appStateUpdateLogger.error("error occurred: " + event)
    throw new RuntimeException("error occurred: " + event)
  }

  override def lookup = AppStateUpdateAPI

  override def createExtension(system: ExtendedActorSystem) = new AppStateUpdateAPIImpl(system)
}
