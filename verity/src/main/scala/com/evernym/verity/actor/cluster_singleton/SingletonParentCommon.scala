package com.evernym.verity.actor.cluster_singleton

import akka.actor.{ActorRef, ActorSystem, Address, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.maintenance.ActorStateCleanupManager
import com.evernym.verity.actor.appStateManager.{ErrorEvent, SeriousSystemError}
import com.evernym.verity.actor.base.{CoreActorExtended, Done}
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.blocking.ResourceBlockingStatusMngr
import com.evernym.verity.actor.cluster_singleton.resourceusagethrottling.warning.ResourceWarningStatusMngr
import com.evernym.verity.actor.cluster_singleton.watcher.WatcherManager
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.actor.cluster_singleton.maintenance.AgentRoutesMigrator
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.ActorNameConstants.{AGENT_ROUTES_MIGRATOR, _}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.Util._
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}


object SingletonParent {
  def props(name: String, ec: ExecutionContext)(implicit agentActorContext: AgentActorContext): Props =
    Props(new SingletonParent(name, ec))
}

class SingletonParent(val name: String, executionContext: ExecutionContext)(implicit val agentActorContext: AgentActorContext)
  extends CoreActorExtended
    with ShardRegionFromActorContext{

  implicit lazy val futureExecutionContext: ExecutionContext = executionContext

  override final def receiveCmd: Receive = {
    case forCmd: ForWatcherManagerChild => forwardToChild(WATCHER_MANAGER, forCmd)
    case forCmd: ForWatcherManager      => forwardToChild(WATCHER_MANAGER, forCmd.cmd)
    case forCmd: ForSingletonChild      => forwardToChild(forCmd.getActorName, forCmd.cmd)

    case sc: SendCmdToAllNodes          => sendCmdToAllNodes(sc)
    case sc: SendNodeAddedAck           => sendNodeAddedAck(sc)

    case RefreshConfigOnAllNodes        => refreshConfigOnAllNodes()
    case oc: OverrideConfigOnAllNodes   => overrideConfigOnAllNodes(oc)
  }

  override def sysCmdHandler: Receive = {
    case me: MemberEvent =>
      me match {
        case me @ (_: MemberUp | _: MemberJoined) =>
          nodes += me.member.address -> NodeParam.empty
          logger.info(s"node ${me.member.address} status changed to ${me.member.status}")
          sendNodeAddedAck(SendNodeAddedAck(me.member.address))
        case me @ (_:MemberExited | _:MemberRemoved | _:MemberLeft) =>
          nodes -= me.member.address
          logger.info(s"node ${me.member.address} status changed to ${me.member.status}")

        case _ => //nothing to do
      }
  }

  private val logger: Logger = getLoggerByClass(classOf[SingletonParent])
  private val cluster: Cluster = akka.cluster.Cluster(context.system)
  private var nodes: Map[Address, NodeParam] = Map.empty[Address, NodeParam]

  private def allSingletonPropsMap: Map[String, Props] =
    Map(
      KeyValueMapper.name -> KeyValueMapper.props(futureExecutionContext),
      WatcherManager.name -> WatcherManager.props(appConfig, futureExecutionContext),
      ResourceBlockingStatusMngr.name -> ResourceBlockingStatusMngr.props(agentActorContext, futureExecutionContext),
      ResourceWarningStatusMngr.name -> ResourceWarningStatusMngr.props(agentActorContext, futureExecutionContext),
      ActorStateCleanupManager.name -> ActorStateCleanupManager.props(appConfig, futureExecutionContext),
      AgentRoutesMigrator.name -> AgentRoutesMigrator.props(appConfig, futureExecutionContext)
    )

  implicit def appConfig: AppConfig = agentActorContext.appConfig

  implicit override def system: ActorSystem = agentActorContext.system

  implicit val timeout: Timeout = buildTimeout(agentActorContext.appConfig,
    TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)

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

  private def getRequiredActor(props: Props, name: String): ActorRef = context.child(name).getOrElse(context.actorOf(props, name))

  private def sendCmdToAllNodeSingletons(cmd: Any): Iterable[Future[Any]] = {
    nodes.map { case (address, nodeParam) =>
      try {
        nodeParam.nodeSingletonActorRef.getOrElse(buildNodeSingletonPath(address)) ? cmd
      } catch {
        case e: Throwable =>
          logger.warn(s"error while sending $cmd to node $address: ${e.getMessage}")
          Future.failed(e)
      }
    }
  }

  private def sendCmdToAllNodeSingletonsWithReducedFuture(cmd: Any): Future[Iterable[Any]] = {
    Future.sequence(sendCmdToAllNodeSingletons(cmd))
  }

  private def forwardToChild(actorName: String, cmd: Any): Unit = {
    allSingletonPropsMap.get(actorName).foreach { props =>
      val actor = getRequiredActor(props, actorName)
      actor forward cmd
    }
  }

  private def sendNodeAddedAck(sc: SendNodeAddedAck): Unit = {
    nodes.get(sc.address).foreach { nodeParam =>
      if(! nodeParam.isAckSent) {
        try {
          logger.debug(s"getting node singleton actor ref for node: ${sc.address}")
          val ar = buildNodeSingletonPath (sc.address)
          logger.debug(s"sending NodeAddedToClusterSingleton to node: ${sc.address}")
          ar ! NodeAddedToClusterSingleton
          nodes += sc.address -> nodeParam.copy(nodeSingletonActorRef = Option(ar), isAckSent = true)
        } catch {
          case e: Throwable =>
            logger.warn(s"error while sending NodeAddedToClusterSingleton message to node ${sc.address}: ${e.getMessage}")
            if (sc.curAttemptCount < sc.maxAttemptCount) {
              val afterSeconds = (sc.curAttemptCount * 3).seconds   //backoff timer
              timers.startSingleTimer(sc.address, sc.copy(curAttemptCount = sc.curAttemptCount + 1), afterSeconds)
            } else {
              val errorMsg = s"max retry attempt reached to send node added acknowledgement to node ${sc.address}"
              publishAppStateEvent(ErrorEvent(SeriousSystemError, CONTEXT_ACTOR_INIT, e, Option(errorMsg)))
            }
        }
      }
    }
  }


  private def sendCmdToAllNodes(sc: SendCmdToAllNodes): Unit = {
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

  private def overrideConfigOnAllNodes(oc: OverrideConfigOnAllNodes): Unit = {
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
  }

  private def refreshConfigOnAllNodes(): Unit = {
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
  }

  private def buildNodeSingletonPath(node :Address): ActorRef = {
    getActorRefFromSelection(s"$node$NODE_SINGLETON_PATH", context.system)
  }

  private def createChildActors(): Unit = {
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
case class ForAgentRoutesMigrator(override val cmd: Any) extends ForSingletonChild {
  def getActorName: String = AGENT_ROUTES_MIGRATOR
}
trait ForWatcherManager extends ForSingletonChild

case object NodeAddedToClusterSingleton extends ActorMessage

case class SendNodeAddedAck(address: Address, curAttemptCount: Int = 1, maxAttemptCount: Int = 10) extends ActorMessage

trait ForWatcherManagerChild extends ActorMessage {
  def cmd: Any
}

object NodeParam {
  def empty: NodeParam = NodeParam()
}
/**
 *
 * @param nodeSingletonActorRef actor ref of node singleton actor
 * @param isAckSent acknowledgement sent
 */
case class NodeParam(nodeSingletonActorRef: Option[ActorRef] = None, isAckSent: Boolean = false)