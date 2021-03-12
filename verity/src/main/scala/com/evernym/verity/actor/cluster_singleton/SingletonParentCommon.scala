package com.evernym.verity.actor.cluster_singleton

import akka.actor.{ActorRef, ActorSystem, Address, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.Exceptions
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.maintenance.ActorStateCleanupManager
import com.evernym.verity.actor.appStateManager.{ErrorEvent, SeriousSystemError}
import com.evernym.verity.actor.base.{CoreActorExtended, Done}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.ResourceBlockingStatusMngr
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.ResourceWarningStatusMngr
import com.evernym.verity.actor.cluster_singleton.watcher.WatcherManager
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.{AllNodeMetricsData, NodeMetricsData}
import com.evernym.verity.util.Util._
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.util.{Failure, Success}


object SingletonParent {
  def props(name: String)(implicit agentActorContext: AgentActorContext): Props = Props(new SingletonParent(name))
}

class SingletonParent(val name: String)(implicit val agentActorContext: AgentActorContext)
  extends CoreActorExtended
    with ShardRegionFromActorContext {

  val logger: Logger = getLoggerByClass(classOf[SingletonParent])
  val cluster: Cluster = akka.cluster.Cluster(context.system)
  var nodes: Set[Address] = Set.empty[Address]

  def allSingletonPropsMap: Map[String, Props] =
    Map(
      KeyValueMapper.name -> KeyValueMapper.props,
      WatcherManager.name -> WatcherManager.props(appConfig),
      ResourceBlockingStatusMngr.name -> ResourceBlockingStatusMngr.props(agentActorContext),
      ResourceWarningStatusMngr.name -> ResourceWarningStatusMngr.props(agentActorContext),
      ActorStateCleanupManager.name -> ActorStateCleanupManager.props(appConfig),
      RouteMaintenanceHelper.name -> RouteMaintenanceHelper.props(appConfig, agentActorContext.agentMsgRouter)
    )

  implicit def appConfig: AppConfig = agentActorContext.appConfig

  implicit override def actorSystem: ActorSystem = agentActorContext.system

  implicit val timeout: Timeout = buildTimeout(agentActorContext.appConfig,
    TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ASK_TIMEOUT_IN_SECONDS)

  override final def preStart(): Unit = {
    try {
      cluster.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberEvent])
    } catch {
      case e: Exception =>
        val errorMsg = s"unable to start cluster singleton child actors: ${Exceptions.getErrorMsg(e)}"
        publishAppStateEvent(ErrorEvent(SeriousSystemError, CONTEXT_ACTOR_INIT, e, Option(errorMsg)))
    }
    createChildActors()
  }

  def getRequiredActor(props: Props, name: String): ActorRef = context.child(name).getOrElse(context.actorOf(props, name))

  def sendCmdToAllNodeSingletons(cmd: Any): Set[Future[Any]] = {
    nodes.map { node =>
      buildNodeSingletonPath(node) ? cmd
    }
  }

  def sendCmdToNode(nodeAddr: Address, cmd: Any): Unit = {
    buildNodeSingletonPath(nodeAddr) ! cmd
  }

  def sendCmdToAllNodeSingletonsWithReducedFuture(cmd: Any): Future[Set[Any]] = {
    Future.sequence(sendCmdToAllNodeSingletons(cmd))
  }

  def forwardToChild(actorName: String, cmd: Any): Unit = {
    allSingletonPropsMap.get(actorName).foreach { props =>
      val actor = getRequiredActor(props, actorName)
      actor forward cmd
    }
  }

  override def sysCmdHandler: Receive = {
    case me: MemberEvent =>
      me match {
        case me @ (_: MemberUp | _:MemberJoined | _:MemberWeaklyUp) =>
          nodes += me.member.address
          logger.info(s"node ${me.member.address} status changed to ${me.member.status}")
          sendCmdToNode(me.member.address, NodeAddedToClusterSingleton)

        case me @ (_:MemberExited | _:MemberRemoved | _:MemberLeft) =>
          nodes = nodes.filterNot(_ == me.member.address)
          logger.info(s"node ${me.member.address} status changed to ${me.member.status}")

        case _ => //nothing to do
      }
  }

  def receiveCommon: Receive = {

    case forCmd: ForWatcherManagerChild => forwardToChild(WATCHER_MANAGER, forCmd)
    case forCmd: ForWatcherManager      => forwardToChild(WATCHER_MANAGER, forCmd.cmd)
    case forCmd: ForSingletonChild      => forwardToChild(forCmd.getActorName, forCmd.cmd)

    case RefreshConfigOnAllNodes =>
      logger.debug(s"refreshing config on nodes: $nodes")
      val f = sendCmdToAllNodeSingletonsWithReducedFuture(RefreshNodeConfig)
      val sndr = sender()
      f.onComplete{
        case Success(_) =>
          sndr ! ConfigRefreshed
        case Failure(e) =>
          sndr ! ConfigRefreshFailed
          logger.error("could not refresh config", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
      }

    case oc: OverrideConfigOnAllNodes =>
      logger.debug(s"override config on nodes: $nodes")
      val f = sendCmdToAllNodeSingletonsWithReducedFuture(OverrideNodeConfig(oc.configStr))
      val sndr = sender()
      f.onComplete{
        case Success(_) =>
          sndr ! ConfigOverridden
        case Failure(e) =>
          sndr ! ConfigOverrideFailed
          logger.error("could not override config", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
      }

    case getMetricsOfAllNode: GetMetricsOfAllNodes =>
      logger.debug(s"fetching metrics from nodes: $nodes")
      val f = sendCmdToAllNodeSingletonsWithReducedFuture(GetNodeMetrics(getMetricsOfAllNode.filters))
      val sndr = sender()
      f.onComplete{
        case Success(result) =>
          sndr ! AllNodeMetricsData(result.asInstanceOf[Set[NodeMetricsData]].toList)
        case Failure(e: Throwable) =>
          logger.error("could not fetch metrics", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
          handleException(e, sndr)
      }

    case sc: SendCmdToAllNodes =>
      logger.debug(s"sending ${sc.cmd} command to node(s): $nodes")
      val sndr = sender()
      val f = sendCmdToAllNodeSingletonsWithReducedFuture(sc.cmd)
      f.onComplete {
        case Success(_) => sndr ! Done
        case Failure(e) =>
          handleException(e, sndr)
          logger.error(s"sending ${sc.cmd} command to node(s) failed", (LOG_KEY_ERR_MSG, Exceptions.getErrorMsg(e)))
      }
  }

  def buildNodeSingletonPath(node :Address): ActorRef = {
    getActorRefFromSelection(s"$node$NODE_SINGLETON_PATH", context.system)
  }

  override final def receiveCmd: Receive = receiveCommon

  def createChildActors(): Unit = {
    allSingletonPropsMap.foreach { e =>
      context.actorOf(e._2, e._1)
    }
  }

}

trait ForSingletonChild extends ActorMessage {
  def cmd: Any
  def getActorName: String
}

case class ForKeyValueMapper(override val cmd: Any) extends ForSingletonChild {
  def getActorName: String = KEY_VALUE_MAPPER_ACTOR_NAME
}
case class ForResourceBlockingStatusMngr(override val cmd: Any) extends ForSingletonChild {
  def getActorName: String = RESOURCE_BLOCKING_STATUS_MNGR
}
case class ForResourceWarningStatusMngr(override val cmd: Any) extends ForSingletonChild {
  def getActorName: String = RESOURCE_WARNING_STATUS_MNGR
}
case class ForActorStateCleanupManager(override val cmd: Any) extends ForSingletonChild {
  def getActorName: String = ACTOR_STATE_CLEANUP_MANAGER
}
trait ForWatcherManager extends ForSingletonChild

case class ForRouteMaintenanceHelper(override val cmd: Any) extends ForSingletonChild {
  def getActorName: String = ROUTE_MAINTENANCE_HELPER
}
case object NodeAddedToClusterSingleton extends ActorMessage

trait ForWatcherManagerChild extends ActorMessage {
  def cmd: Any
}
