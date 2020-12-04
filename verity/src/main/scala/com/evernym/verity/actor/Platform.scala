package com.evernym.verity.actor

import java.time.ZoneId

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.singleton._
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity._
import com.evernym.verity.actor.ShardUtil._
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.agency.{AgencyAgent, AgencyAgentPairwise}
import com.evernym.verity.actor.agent.msgrouter.AgentRouteStore
import com.evernym.verity.actor.agent.user.{UserAgent, UserAgentPairwise}
import com.evernym.verity.actor.cluster_singleton.SingletonParent
import com.evernym.verity.actor.itemmanager.{ItemContainer, ItemManager}
import com.evernym.verity.actor.metrics.{ActivityTracker, ActivityWindow}
import com.evernym.verity.actor.msg_tracer.MsgTracingRegionActors
import com.evernym.verity.actor.node_singleton.NodeSingleton
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.actor.segmentedstates.SegmentedStateStore
import com.evernym.verity.actor.url_mapper.UrlStore
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.protocol.actor.ActorProtocol
import com.evernym.verity.util.TimeZoneUtil.UTCZoneId
import com.evernym.verity.util.Util._

import scala.concurrent.Future

class Platform(val aac: AgentActorContext)
  extends MsgTracingRegionActors
    with LegacyRegionActors
    with MaintenanceRegionActors
    with ShardUtil {

  implicit def agentActorContext: AgentActorContext = aac
  implicit def appConfig: AppConfig = agentActorContext.appConfig
  implicit def actorSystem: ActorSystem = agentActorContext.system

  implicit lazy val timeout: Timeout = buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS,
    DEFAULT_GENERAL_ASK_TIMEOUT_IN_SECONDS)

  implicit val zoneId: ZoneId = UTCZoneId

  val nodeSingleton: ActorRef = agentActorContext.system.actorOf(NodeSingleton.props(appConfig), name = "node-singleton")

  def buildProp(prop: Props, dispatcherNameOpt: Option[String]=None): Props = {
    dispatcherNameOpt.map { dn =>
      val cdnOpt = agentActorContext.appConfig.getConfigOption(dn)
      cdnOpt.map { _ =>
        agentActorContext.system.dispatchers.lookup(dn)
        prop.withDispatcher(dn)
      }.getOrElse(prop)
    }.getOrElse(prop)
  }

  //agency agent actor
  val agencyAgentRegion: ActorRef = createRegion(
    AGENCY_AGENT_REGION_ACTOR_NAME,
    buildProp(Props(new AgencyAgent(agentActorContext)), Option(ACTOR_DISPATCHER_NAME_AGENCY_AGENT)))

  object agencyAgent extends ShardActorObject {
    def !(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender): Unit = {
      agencyAgentRegion.tell(ForIdentifier(id, msg), sender)
    }
    def ?(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender): Future[Any] = {
      agencyAgentRegion ? ForIdentifier(id, msg)
    }
  }

  //agency agent actor for pairwise connection
  val agencyAgentPairwiseRegion: ActorRef = createRegion(
    AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
    buildProp(Props(new AgencyAgentPairwise(agentActorContext)), Option(ACTOR_DISPATCHER_NAME_AGENCY_AGENT_PAIRWISE)))



  //agent actor
  val userAgentRegion: ActorRef = createRegion(
    USER_AGENT_REGION_ACTOR_NAME,
    buildProp(Props(new UserAgent(agentActorContext)), Option(ACTOR_DISPATCHER_NAME_USER_AGENT)))

  //agent actor for pairwise connection
  val userAgentPairwiseRegion: ActorRef = createRegion(
    USER_AGENT_PAIRWISE_REGION_ACTOR_NAME,
    buildProp(Props(new UserAgentPairwise(agentActorContext)), Option(ACTOR_DISPATCHER_NAME_USER_AGENT_PAIRWISE)))

  //activity tracker actor
  val activityTrackerRegion: ActorRef = createRegion(
    ACTIVITY_TRACKER_REGION_ACTOR_NAME,
    buildProp(
      Props(new ActivityTracker(agentActorContext.appConfig, agentActorContext.agentMsgRouter)),
      Option(ACTOR_DISPATCHER_NAME_ACTIVITY_TRACKER)
  ))

  object agentPairwise extends ShardActorObject {
    def !(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender): Unit = {
      userAgentPairwiseRegion.tell(ForIdentifier(id, msg), sender)
    }
    def ?(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender): Future[Any] = {
      userAgentPairwiseRegion ? ForIdentifier(id, msg)
    }
  }

  def createCusterSingletonManagerActor(singletonProps: Props): ActorRef = {
    agentActorContext.system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = singletonProps,
        terminationMessage = PoisonPill,
        settings = ClusterSingletonManagerSettings(agentActorContext.system)
      ),
      name = CLUSTER_SINGLETON_MANAGER)
  }

  def createClusterSingletonProxyActor(singletonManagerPath: String): ActorRef = {
    agentActorContext.system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = singletonManagerPath,
        settings = ClusterSingletonProxySettings(agentActorContext.system)),
      name = CLUSTER_SINGLETON_MANAGER_PROXY)
  }

  //token manager
  val tokenToActorItemMapperRegion: ActorRef = createRegion(
    TOKEN_TO_ACTOR_ITEM_MAPPER_REGION_ACTOR_NAME,
    TokenToActorItemMapper.props(agentActorContext.appConfig),
    forTokenShardIdExtractor,
    forTokenEntityIdExtractor
  )

  object tokenToActorItemMapper extends ShardActorObject {
    def !(msg: Any)(implicit token: String, sender: ActorRef = Actor.noSender): Unit = {
      tokenToActorItemMapperRegion.tell(ForToken(token, msg), sender)
    }
    def ?(msg: Any)(implicit token: String, sender: ActorRef = Actor.noSender): Future[Any] = {
      tokenToActorItemMapperRegion ? ForToken(token, msg)
    }
  }

  //url store
  val urlStoreRegion: ActorRef = createRegion(
    URL_STORE_REGION_ACTOR_NAME,
    UrlStore.props(agentActorContext.appConfig),
    forUrlMapperShardIdExtractor,
    forUrlMapperEntityIdExtractor
  )

  object urlStore extends ShardActorObject {
    def !(msg: Any)(implicit hashed: String, sender: ActorRef = Actor.noSender): Unit = {
      urlStoreRegion.tell(ForUrlStore(hashed, msg), sender)
    }
    def ?(msg: Any)(implicit hashed: String, sender: ActorRef = Actor.noSender): Future[Any] = {
      urlStoreRegion ? ForUrlStore(hashed, msg)
    }
  }

  //resource usage tracker region actor
  val resourceUsageTrackerRegion: ActorRef = createRegion(
    RESOURCE_USAGE_TRACKER_REGION_ACTOR_NAME,
    ResourceUsageTracker.props(agentActorContext.appConfig, agentActorContext.actionExecutor))

  //other region actors
  val agentRouteStoreRegion: ActorRef = createRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME, AgentRouteStore.props)
  val itemManagerRegion: ActorRef = createRegion(ITEM_MANAGER_REGION_ACTOR_NAME, ItemManager.props)
  val itemContainerRegion: ActorRef = createRegion(ITEM_CONTAINER_REGION_ACTOR_NAME, ItemContainer.props)

  // protocol region actors
  val protocolRegions = agentActorContext.protocolRegistry.entries.map { e =>
    val ap = ActorProtocol(e.protoDef)
    val region = createRegion(
      ap.typeName,
      ap.props(agentActorContext))
    ap.typeName -> region
  }.toMap


  //segmented state region actors
  val segmentedStateRegions = agentActorContext.protocolRegistry.entries.filter(_.protoDef.segmentedStateName.isDefined).map { e =>
    val typeName = SegmentedStateStore.buildTypeName(e.protoDef.msgFamily.protoRef, e.protoDef.segmentedStateName.get)
    val region = createRegion(
      typeName,
      SegmentedStateStore.props(agentActorContext.appConfig))
    typeName -> region
  }.toMap

  createCusterSingletonManagerActor(SingletonParent.props(CLUSTER_SINGLETON_PARENT))

  val singletonParentProxy: ActorRef =
    createClusterSingletonProxyActor(s"/user/$CLUSTER_SINGLETON_MANAGER")

}
