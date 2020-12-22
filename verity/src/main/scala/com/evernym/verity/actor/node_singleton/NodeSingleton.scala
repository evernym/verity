package com.evernym.verity.actor.node_singleton

import akka.pattern.ask
import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor._
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.base.{BaseNonPersistentActor, Done}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.{GetBlockedList, UpdateBlockingStatus, UsageBlockingStatusChunk}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.{GetWarnedList, UpdateWarningStatus, UsageWarningStatusChunk}
import com.evernym.verity.actor.cluster_singleton.{ForResourceBlockingStatusMngr, ForResourceWarningStatusMngr, NodeAddedToClusterSingleton}
import com.evernym.verity.actor.maintenance.{ActorParam, ReadOnlyPersistentActor}
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.apphealth.AppStateManager
import com.evernym.verity.config.{AppConfig, AppConfigWrapper}
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.util.Util._
import com.typesafe.config.ConfigFactory


object NodeSingleton {
  def props(appConfig: AppConfig): Props = Props(new NodeSingleton(appConfig))
}

class NodeSingleton(val appConfig: AppConfig) extends BaseNonPersistentActor with HasActorResponseTimeout {

  def sendGetBlockingList(singletonActorRef: ActorRef): Unit =  {
    singletonActorRef ! ForResourceBlockingStatusMngr(GetBlockedList(onlyBlocked = false, onlyUnblocked = false,
      onlyActive = true, inChunks = true))
  }

  def sendGetWarningList(singletonActorRef: ActorRef): Unit =  {
    singletonActorRef ! ForResourceWarningStatusMngr(GetWarnedList(onlyWarned = false, onlyUnwarned = false,
      onlyActive = true, inChunks = true))
  }

  def receiveCmd: Receive = {

    case NodeAddedToClusterSingleton =>
      logger.info(s"configuration refresh started...")
      sendGetBlockingList(sender)
      sendGetWarningList(sender)
      logger.info(s"configuration refresh started...")

    case RefreshNodeConfig =>
      logger.info(s"configuration refresh started...")
      AppConfigWrapper.reload()
      sender ! NodeConfigRefreshed
      logger.info(s"configuration refresh done !!")

    case onc: OverrideNodeConfig =>
      logger.info(s"configuration override started...")
      try {
        val newConfig = ConfigFactory.parseString(onc.configStr)
        AppConfigWrapper.setConfig(newConfig.withFallback(AppConfigWrapper.config))
        sender ! NodeConfigRefreshed
        logger.info(s"configuration override done !!")
      } finally {

      }

    case getNodeMetrics: GetNodeMetrics =>
      logger.debug(s"fetching metrics data...")
      sender ! MetricsReader.getNodeMetrics(getNodeMetrics.filters)
      logger.debug(s"metrics data fetched !!")

    case ResetNodeMetrics =>
      logger.info(s"start resetting metrics...")
      MetricsReader.resetNodeMetrics()
      sender ! NodeMetricsResetDone
      logger.info(s"resetting metrics done !!")

    case uws: UpdateWarningStatus =>
      ResourceWarningStatusMngrCache.processEvent(uws)
      sender ! Done

    case cuws: UsageWarningStatusChunk =>
      ResourceWarningStatusMngrCache.initWarningList(cuws)
      sender ! Done

    case ubs: UpdateBlockingStatus =>
      ResourceBlockingStatusMngrCache.processEvent(ubs)
      sender ! Done

    case cubs: UsageBlockingStatusChunk =>
      ResourceBlockingStatusMngrCache.initBlockingList(cubs)
      sender ! Done

    case spt: StartProgressTracking =>
      MsgProgressTrackerCache.startProgressTracking(spt.trackingId)
      sender ! Done

    case spt: StopProgressTracking =>
      MsgProgressTrackerCache.stopProgressTracking(spt.trackingId)
      sender ! Done

    case DrainNode =>
      logger.info(s"draining started...")
      AppStateManager.drain(context.system)
      sender ! DrainInitiated
      logger.info(s"draining in progress !!")

    case p: PersistentActorQueryParam =>
      val ar = getRequiredActor(ReadOnlyPersistentActor.prop(appConfig, p.actorParam), p.actorParam.id)
      val sndr = sender()
      val fut = ar ? p.cmd
      fut.map(r => sndr ! r)
  }

  def getRequiredActor(props: Props, name: String): ActorRef =
    context.child(name).getOrElse(context.actorOf(props, name))
}

case object DrainNode extends ActorMessageObject
case object DrainInitiated extends ActorMessageObject
case class PersistentActorQueryParam(actorParam: ActorParam, cmd: Any)
  extends ActorMessageClass