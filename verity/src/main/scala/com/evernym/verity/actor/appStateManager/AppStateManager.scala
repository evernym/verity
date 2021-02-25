package com.evernym.verity.actor.appStateManager

import akka.actor.{ActorLogging, Props}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.{AppVersion, BuildInfo}
import com.evernym.verity.actor.base.CoreActorExtended

class AppStateManager(val notifierService: SysServiceNotifier,
                      val shutdownService: SysShutdownProvider)
  extends CoreActorExtended
    with AppStateManagerBase
    with ActorLogging {

  val appVersion: AppVersion = BuildInfo.version

  /**
   * to be supplied by implemented class
   *
   * @return
   */
  override def receiveCmd: Receive = handleEvents orElse handleStateRequests

  //handles events which may/will change state
  // these are published events, and sender may not be available for these events
  // and hence should not respond anything back
  private def handleEvents: Receive = {
    case se: SuccessEvent           => processSuccessEvent (se)
    case ee: ErrorEvent             => processErrorEvent (ee)
    case StartDraining              => startDraining()
    case RecoverIfNeeded(context)   => recoverIfNeeded(context)
  }

  //these are commands sent directly to this actor (not published events)
  private def handleStateRequests: Receive = {
    case GetHeartbeat               => sender ! getHeartbeat
    case GetEvents                  => sender ! getAllEvents
    case GetCurrentState            => sender ! getState
    case GetDetailedAppState        => sender ! getDetailedAppState
  }

  override def beforeStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[AppStateEvent])
  }
}

trait AppStateRequest extends ActorMessage

trait AppStateEvent extends ActorMessage

object AppStateManager {
  def props(sysServiceNotifier: SysServiceNotifier, shutdownService: SysShutdownProvider): Props =
    Props(new AppStateManager(sysServiceNotifier, shutdownService))
}