package com.evernym.verity.actor.node_singleton

import akka.actor.typed.eventstream.EventStream.Subscribe
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.HasSingletonParentProxy
import com.evernym.verity.actor.appStateManager.StartDraining
import com.evernym.verity.actor.base.{CoreActorExtended, Done}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{GetBlockedList, UpdateBlockingStatus, UsageBlockingStatusChunk}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.{GetWarnedList, UpdateWarningStatus, UsageWarningStatusChunk}
import com.evernym.verity.actor.cluster_singleton.{ForResourceBlockingStatusMngr, ForResourceWarningStatusMngr, NodeAddedToClusterSingleton}
import com.evernym.verity.actor.maintenance.{ActorParam, ReadOnlyPersistentActor}
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.MetricsWriterExtension
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.typesafe.config.ConfigFactory


class NodeSingleton(val appConfig: AppConfig)
  extends CoreActorExtended
    with HasActorResponseTimeout
    with HasSingletonParentProxy
    with HasAppConfig {

  private val logger = getLoggerByClass(getClass)

  override def receiveCmd: Receive = handleEvents orElse handleCmds

  def handleCmds: Receive = {

    case NodeAddedToClusterSingleton =>
      logger.info(s"sending blocked/warned started...")
      sendGetBlockingList(sender)
      sendGetWarningList(sender)
      logger.info(s"sending blocked/warned done !!")

    case RefreshNodeConfig =>
      logger.info(s"configuration refresh started...")
      appConfig.reload()
      sender ! NodeConfigRefreshed
      logger.info(s"configuration refresh done !!")
      MetricsWriterExtension(context.system).updateFilters(appConfig.config)

    case onc: OverrideNodeConfig =>
      logger.info(s"configuration override started...")
      try {
        val newConfig = ConfigFactory.parseString(onc.configStr)
        appConfig.setConfig(newConfig.withFallback(appConfig.config))
        sender ! NodeConfigRefreshed
        logger.info(s"configuration override done !!")
      } catch {
        case e: Throwable =>
        logger.error("configuration override failed: " + e.getMessage)
      }

    case uws: UpdateWarningStatus =>
      ResourceWarningStatusMngrCache(context.system).processEvent(uws)
      sender ! Done

    case cuws: UsageWarningStatusChunk =>
      ResourceWarningStatusMngrCache(context.system).initWarningList(cuws)
      sender ! Done

    case ubs: UpdateBlockingStatus =>
      ResourceBlockingStatusMngrCache(context.system).processEvent(ubs)
      sender ! Done

    case cubs: UsageBlockingStatusChunk =>
      ResourceBlockingStatusMngrCache(context.system).initBlockingList(cubs)
      sender ! Done

    case spt: StartProgressTracking =>
      MsgProgressTrackerCache(context.system).startProgressTracking(spt.trackingId)
      sender ! Done

    case spt: StopProgressTracking =>
      MsgProgressTrackerCache(context.system).stopProgressTracking(spt.trackingId)
      sender ! Done

    case DrainNode =>
      logger.info(s"draining started...")
      publishAppStateEvent(StartDraining)
      sender ! DrainInitiated
      logger.info(s"draining in progress !!")

    case p: PersistentActorQueryParam =>
      val ar = getRequiredActor(ReadOnlyPersistentActor.prop(appConfig, p.actorParam), p.actorParam.id)
      val sndr = sender()
      val fut = ar ? p.cmd
      fut.map(r => sndr ! r)
  }

  //handles published events
  private def handleEvents: Receive = {
    case SingletonProxyEvent(cmd: ActorMessage)   => singletonParentProxyActor ! cmd
  }

  private def sendGetBlockingList(singletonActorRef: ActorRef): Unit =  {
    singletonActorRef ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = false, onlyUnblocked = false,
      onlyActive = true, inChunks = true))
  }

  private def sendGetWarningList(singletonActorRef: ActorRef): Unit =  {
    singletonActorRef ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = false, onlyUnwarned = false,
      onlyActive = true, inChunks = true))
  }

  private def getRequiredActor(props: Props, name: String): ActorRef =
    context.child(name).getOrElse(context.actorOf(props, name))

  override def beforeStart(): Unit = {
    import akka.actor.typed.scaladsl.adapter._
    context.system.toTyped.eventStream ! Subscribe[SingletonProxyEvent](self)
  }
}

case object DrainNode extends ActorMessage
case object DrainInitiated extends ActorMessage
case class PersistentActorQueryParam(actorParam: ActorParam, cmd: Any)
  extends ActorMessage

object NodeSingleton {
  def props(appConfig: AppConfig): Props = Props(new NodeSingleton(appConfig))
}

case class SingletonProxyEvent(cmd: ActorMessage) extends ActorMessage
