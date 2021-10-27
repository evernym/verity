package com.evernym.verity.actor

import akka.actor.{Actor, ActorRef, ActorSystem, Extension, ExtensionId, PoisonPill, Props}
import akka.cluster.singleton._
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.util2._
import com.evernym.verity.actor.ShardUtil._
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.agency.{AgencyAgent, AgencyAgentPairwise}
import com.evernym.verity.actor.agent.msgrouter.Route
import com.evernym.verity.actor.agent.user.{UserAgent, UserAgentPairwise}
import com.evernym.verity.actor.cluster_singleton.SingletonParent
import com.evernym.verity.actor.itemmanager.{ItemContainer, ItemManager}
import com.evernym.verity.actor.metrics.{CollectionsMetricCollector, LibindyMetricsCollector}
import com.evernym.verity.actor.msg_tracer.MsgTracingRegionActors
import com.evernym.verity.actor.node_singleton.NodeSingleton
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.actor.segmentedstates.SegmentedStateStore
import com.evernym.verity.actor.url_mapper.UrlStore
import com.evernym.verity.actor.wallet.WalletActor
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.container.actor.ActorProtocol
import com.evernym.verity.util.TimeZoneUtil.UTCZoneId
import com.evernym.verity.util.Util._

import java.time.ZoneId
import com.evernym.verity.actor.appStateManager.{AppStateManager, SDNotifyService, SysServiceNotifier, SysShutdownProvider, SysShutdownService}
import com.evernym.verity.actor.metrics.activity_tracker.ActivityTracker
import com.evernym.verity.actor.resourceusagethrottling.helper.UsageViolationActionExecutor
import com.evernym.verity.actor.typed.base.UserGuardian
import com.evernym.verity.vdrtools.Libraries
import com.evernym.verity.util.healthcheck.HealthChecker
import scala.concurrent.Future
import scala.concurrent.duration._

class Platform(val aac: AgentActorContext, services: PlatformServices, val executionContextProvider: ExecutionContextProvider)
  extends MsgTracingRegionActors
    with LegacyRegionActors
    with MaintenanceRegionActors
    with ShardUtil {

  implicit def agentActorContext: AgentActorContext = aac
  implicit def appConfig: AppConfig = agentActorContext.appConfig
  implicit def actorSystem: ActorSystem = agentActorContext.system

  def healthChecker: HealthChecker = HealthChecker(aac, aac.system, executionContextProvider.futureExecutionContext)

  implicit lazy val timeout: Timeout = buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS,
    DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)
  implicit val zoneId: ZoneId = UTCZoneId

  //initialize required libraries (libindy/libmysqlstorage etc)
  Libraries.initialize(appConfig)

  def startExtensionIfEnabled[T <: Extension](t: ExtensionId[T], confPath: String)(start: T => Unit): Unit = {
    val isEnabled = appConfig
      .getBooleanOption(confPath)
      .getOrElse(false)

    if (isEnabled) {
      start(t.get(actorSystem))
    }
  }

  //initialize app state manager
  val appStateManager: ActorRef = agentActorContext.system.actorOf(
    AppStateManager.props(
      appConfig,
      services.sysServiceNotifier,
      services.sysShutdownService,
      executionContextProvider.futureExecutionContext
    ), name = "app-state-manager")

  val nodeSingleton: ActorRef = agentActorContext.system.actorOf(NodeSingleton.props(appConfig, executionContextProvider.futureExecutionContext), name = "node-singleton")

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
    buildProp(
      Props(
        new AgencyAgent(
          agentActorContext,
          executionContextProvider.futureExecutionContext
        )
      ),
      Option(AGENCY_AGENT_ACTOR_DISPATCHER_NAME)
    )
  )

  //agency agent actor for pairwise connection
  val agencyAgentPairwiseRegion: ActorRef = createPersistentRegion(
    AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
    buildProp(
      Props(
        new AgencyAgentPairwise(
          agentActorContext,
          executionContextProvider.futureExecutionContext
        )
      ),
      Option(AGENCY_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME)
    )
  )

  //agent actor
  val userAgentRegion: ActorRef = createPersistentRegion(
    USER_AGENT_REGION_ACTOR_NAME,
    buildProp(
      Props(
        new UserAgent(
          agentActorContext,
          collectionsMetricsCollector,
          executionContextProvider.futureExecutionContext
        )
      ),
      Option(USER_AGENT_ACTOR_DISPATCHER_NAME)
    )
  )

  //agent actor for pairwise connection
  val userAgentPairwiseRegion: ActorRef = createPersistentRegion(
    USER_AGENT_PAIRWISE_REGION_ACTOR_NAME,
    buildProp(
      Props(
        new UserAgentPairwise(
          agentActorContext,
          collectionsMetricsCollector,
          executionContextProvider.futureExecutionContext
        )
      ),
      Option(USER_AGENT_PAIRWISE_ACTOR_DISPATCHER_NAME)
    )
  )

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
      Props(
        new ActivityTracker(
          agentActorContext.appConfig,
          agentActorContext.agentMsgRouter,
          executionContextProvider.futureExecutionContext
        )
      ),
      Option(ACTIVITY_TRACKER_ACTOR_DISPATCHER_NAME)
  ))

  //wallet actor
  val walletActorRegion: ActorRef = createNonPersistentRegion(
    WALLET_REGION_ACTOR_NAME,
    buildProp(
      Props(
        new WalletActor(
          agentActorContext.appConfig,
          agentActorContext.poolConnManager,
          executionContextProvider.futureExecutionContext
        )
      ),
      Option(WALLET_ACTOR_ACTOR_DISPATCHER_NAME)
    ),
    passivateIdleEntityAfter = Option(
      passivateDuration(NON_PERSISTENT_WALLET_ACTOR_PASSIVATE_TIME_IN_SECONDS, 600.seconds)
    )
  )

  //token manager
  val tokenToActorItemMapperRegion: ActorRef = createPersistentRegion(
    TOKEN_TO_ACTOR_ITEM_MAPPER_REGION_ACTOR_NAME,
    TokenToActorItemMapper.props(executionContextProvider.futureExecutionContext)(agentActorContext.appConfig),
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
    UrlStore.props(agentActorContext.appConfig, executionContextProvider.futureExecutionContext),
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
  val resourceUsageTrackerRegion: ActorRef = {
    val actionExecutor = new UsageViolationActionExecutor(actorSystem, appConfig)
    createPersistentRegion(
      RESOURCE_USAGE_TRACKER_REGION_ACTOR_NAME,
      ResourceUsageTracker.props(agentActorContext.appConfig, actionExecutor, executionContextProvider.futureExecutionContext))
  }

  //other region actors

  val routeRegion: ActorRef =
    createPersistentRegion(ROUTE_REGION_ACTOR_NAME, Route.props(executionContextProvider.futureExecutionContext))

  val itemManagerRegion: ActorRef =
    createPersistentRegion(
      ITEM_MANAGER_REGION_ACTOR_NAME,
      ItemManager.props(executionContextProvider.futureExecutionContext)
    )
  val itemContainerRegion: ActorRef =
    createPersistentRegion(
      ITEM_CONTAINER_REGION_ACTOR_NAME,
      ItemContainer.props(executionContextProvider.futureExecutionContext)
    )

  // protocol region actors
  val protocolRegions: Map[String, ActorRef] = agentActorContext.protocolRegistry
    .entries
    .map { e =>
      val ap = ActorProtocol(e.protoDef)
      val region = createProtoActorRegion(
        ap.typeName,
        ap.props(agentActorContext, executionContextProvider.futureExecutionContext))
      ap.typeName -> region
    }.toMap


  //segmented state region actors
  val segmentedStateRegions: Map[String, ActorRef] = agentActorContext.protocolRegistry
    .entries.filter(_.protoDef.segmentStoreStrategy.isDefined)
    .map { e =>
      val typeName = SegmentedStateStore.buildTypeName(e.protoDef.protoRef)
      val region = createPersistentRegion(
        typeName,
        SegmentedStateStore.props(agentActorContext.appConfig, executionContextProvider.futureExecutionContext))
      typeName -> region
    }.toMap

  createCusterSingletonManagerActor(
    SingletonParent.props(
      CLUSTER_SINGLETON_PARENT,
      executionContextProvider.futureExecutionContext
    )
  )

  //Agent to collect metrics from Libindy
  val libIndyMetricsCollector: ActorRef =
    agentActorContext.system.actorOf(Props(new LibindyMetricsCollector(executionContextProvider.futureExecutionContext)), name = LIBINDY_METRICS_TRACKER)

  //Agent to collect collections metrics for collections
  lazy val collectionsMetricsCollector: ActorRef =
    agentActorContext.system.actorOf(Props(new CollectionsMetricCollector()), name = COLLECTIONS_METRICS_COLLECTOR)

  val singletonParentProxy: ActorRef =
    createClusterSingletonProxyActor(s"/user/$CLUSTER_SINGLETON_MANAGER")

  import akka.actor.typed.scaladsl.adapter._
  actorSystem.spawn(UserGuardian(agentActorContext, executionContextProvider.futureExecutionContext), "guardian")

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
    appConfig.getIntOption(confName) match {
      case Some(duration) => duration.second
      case None           => defaultDurationInSeconds
    }
  }

  val appStateCoordinator = new AppStateCoordinator(
    appConfig,
    actorSystem,
    appStateManager)(agentActorContext.futureExecutionContext)
}

trait PlatformServices {
  def sysServiceNotifier: SysServiceNotifier
  def sysShutdownService: SysShutdownProvider
}

object PlatformServices extends PlatformServices {
  override def sysServiceNotifier: SysServiceNotifier = SDNotifyService
  override def sysShutdownService: SysShutdownProvider = SysShutdownService
}
