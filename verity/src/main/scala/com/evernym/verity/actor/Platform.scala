package com.evernym.verity.actor

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.cluster.singleton._
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity._
import com.evernym.verity.actor.ShardUtil._
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.agency.{AgencyAgent, AgencyAgentPairwise}
import com.evernym.verity.actor.agent.msgrouter.AgentRouteStore
import com.evernym.verity.actor.agent.user.{UserAgent, UserAgentPairwise}
import com.evernym.verity.actor.cluster_singleton.SingletonParent
import com.evernym.verity.actor.itemmanager.{ItemContainer, ItemManager}
import com.evernym.verity.actor.metrics.{ActivityTracker, CollectionsMetricCollector, LibindyMetricsCollector}
import com.evernym.verity.actor.msg_tracer.MsgTracingRegionActors
import com.evernym.verity.actor.node_singleton.NodeSingleton
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.actor.segmentedstates.SegmentedStateStore
import com.evernym.verity.actor.url_mapper.UrlStore
import com.evernym.verity.actor.wallet.WalletActor
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.container.actor.ActorProtocol
import com.evernym.verity.util.TimeZoneUtil.UTCZoneId
import com.evernym.verity.util.Util._
import java.time.ZoneId

import com.evernym.verity.actor.appStateManager.{AppStateManager, SDNotifyService, SysServiceNotifier, SysShutdownProvider, SysShutdownService}
import com.evernym.verity.libs.Libraries
import com.evernym.verity.metrics.MetricsReader

import scala.concurrent.Future
import scala.concurrent.duration._

class Platform(val aac: AgentActorContext, services: PlatformServices)
  extends MsgTracingRegionActors
    with LegacyRegionActors
    with MaintenanceRegionActors
    with ShardUtil {

  implicit def agentActorContext: AgentActorContext = aac
  implicit def appConfig: AppConfig = agentActorContext.appConfig
  implicit def actorSystem: ActorSystem = agentActorContext.system

  implicit lazy val timeout: Timeout = buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS,
    DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)
  implicit val zoneId: ZoneId = UTCZoneId

  //initialize required libraries (libindy/libmysqlstorage etc)
  Libraries.initialize(appConfig)

  //start prometheus reporter
  // intention behind this is to have 'PrometheusReporter' get loaded and it's configuration is validated as well
  MetricsReader

  //initialize app state manager
  val appStateManager: ActorRef = agentActorContext.system.actorOf(
    AppStateManager.props(appConfig, services.sysServiceNotifier, services.sysShutdownService), name = "app-state-manager")

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
  val agencyAgentRegion: ActorRef = createPersistentRegion(
    AGENCY_AGENT_REGION_ACTOR_NAME,
    buildProp(Props(new AgencyAgent(agentActorContext)), Option(AGENCY_AGENT_ACTOR_DISPATCHER_NAME)))

  //agency agent actor for pairwise connection
  val agencyAgentPairwiseRegion: ActorRef = createPersistentRegion(
    AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
    buildProp(Props(new AgencyAgentPairwise(agentActorContext)), Option(AGENCY_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME)))

  //agent actor
  val userAgentRegion: ActorRef = createPersistentRegion(
    USER_AGENT_REGION_ACTOR_NAME,
    buildProp(Props(new UserAgent(agentActorContext, collectionsMetricsCollector)), Option(USER_AGENT_ACTOR_DISPATCHER_NAME)))

  //agent actor for pairwise connection
  val userAgentPairwiseRegion: ActorRef = createPersistentRegion(
    USER_AGENT_PAIRWISE_REGION_ACTOR_NAME,
    buildProp(Props(new UserAgentPairwise(agentActorContext, collectionsMetricsCollector)), Option(USER_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME)))

  object agencyAgent extends ShardActorObject {
    def !(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender): Unit = {
      agencyAgentRegion.tell(ForIdentifier(id, msg), sender)
    }
    def ?(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender): Future[Any] = {
      agencyAgentRegion ? ForIdentifier(id, msg)
    }
  }

  object agentPairwise extends ShardActorObject {
    def !(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender): Unit = {
      userAgentPairwiseRegion.tell(ForIdentifier(id, msg), sender)
    }
    def ?(msg: Any)(implicit id: String, sender: ActorRef = Actor.noSender): Future[Any] = {
      userAgentPairwiseRegion ? ForIdentifier(id, msg)
    }
  }

  //activity tracker actor
  val activityTrackerRegion: ActorRef = createPersistentRegion(
    ACTIVITY_TRACKER_REGION_ACTOR_NAME,
    buildProp(
      Props(new ActivityTracker(agentActorContext.appConfig, agentActorContext.agentMsgRouter)),
      Option(ACTIVITY_TRACKER_ACTOR_DISPATCHER_NAME)
  ))

  //wallet actor
  val walletActorRegion: ActorRef = createNonPersistentRegion(
    WALLET_REGION_ACTOR_NAME,
    buildProp(
      Props(new WalletActor(agentActorContext.appConfig, agentActorContext.poolConnManager)),
      Option(WALLET_ACTOR_ACTOR_DISPATCHER_NAME)
    ),
    passivateIdleEntityAfter = Option(
      passivateDuration(NON_PERSISTENT_WALLET_ACTOR_PASSIVATE_TIME_IN_SECONDS, 600.seconds)
    )
  )

  //token manager
  val tokenToActorItemMapperRegion: ActorRef = createPersistentRegion(
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
  val urlStoreRegion: ActorRef = createPersistentRegion(
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
  val resourceUsageTrackerRegion: ActorRef = createPersistentRegion(
    RESOURCE_USAGE_TRACKER_REGION_ACTOR_NAME,
    ResourceUsageTracker.props(agentActorContext.appConfig, agentActorContext.actionExecutor))

  //other region actors
  val agentRouteStoreRegion: ActorRef =
    createPersistentRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME, AgentRouteStore.props)
  val itemManagerRegion: ActorRef =
    createPersistentRegion(ITEM_MANAGER_REGION_ACTOR_NAME, ItemManager.props)
  val itemContainerRegion: ActorRef =
    createPersistentRegion(ITEM_CONTAINER_REGION_ACTOR_NAME, ItemContainer.props)

  // protocol region actors
  val protocolRegions: Map[String, ActorRef] = agentActorContext.protocolRegistry
    .entries
    .map { e =>
      val ap = ActorProtocol(e.protoDef)
      val region = createProtoActorRegion(
        ap.typeName,
        ap.props(agentActorContext))
      ap.typeName -> region
    }.toMap


  //segmented state region actors
  val segmentedStateRegions: Map[String, ActorRef] = agentActorContext.protocolRegistry
    .entries.filter(_.protoDef.segmentedStateName.isDefined)
    .map { e =>
      val typeName = SegmentedStateStore.buildTypeName(e.protoDef.msgFamily.protoRef, e.protoDef.segmentedStateName.get)
      val region = createPersistentRegion(
        typeName,
        SegmentedStateStore.props(agentActorContext.appConfig))
      typeName -> region
    }.toMap

  createCusterSingletonManagerActor(SingletonParent.props(CLUSTER_SINGLETON_PARENT))

  //Agent to collect metrics from Libindy
  val libIndyMetricsCollector: ActorRef =
    agentActorContext.system.actorOf(Props(new LibindyMetricsCollector()), name = LIBINDY_METRICS_TRACKER)

  //Agent to collect collections metrics for collections
  lazy val collectionsMetricsCollector: ActorRef =
    agentActorContext.system.actorOf(Props(new CollectionsMetricCollector()), name = COLLECTIONS_METRICS_COLLECTOR)

  val singletonParentProxy: ActorRef =
    createClusterSingletonProxyActor(s"/user/$CLUSTER_SINGLETON_MANAGER")

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

  /**
   * utility function to compute passivation time
   * @param confName
   * @param defaultDurationInSeconds
   * @return
   */
  def passivateDuration(confName: String,
                        defaultDurationInSeconds: FiniteDuration): FiniteDuration = {
    //assumption is that the config duration is in seconds
    appConfig.getConfigIntOption(confName) match {
      case Some(duration) => duration.second
      case None           => defaultDurationInSeconds
    }
  }
}

trait PlatformServices {
  def sysServiceNotifier: SysServiceNotifier
  def sysShutdownService: SysShutdownProvider
}

object PlatformServices extends PlatformServices {
  override def sysServiceNotifier: SysServiceNotifier = SDNotifyService
  override def sysShutdownService: SysShutdownProvider = SysShutdownService
}