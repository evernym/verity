package com.evernym.verity.actor

import akka.actor.{Actor, ActorRef, ActorSystem, Extension, ExtensionId, PoisonPill, Props}
import akka.cluster.singleton._
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.util2._
import com.evernym.verity.actor.ShardUtil._
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.agency.{AgencyAgent, AgencyAgentPairwise}
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, Route}
import com.evernym.verity.actor.agent.user.{UserAgent, UserAgentPairwise}
import com.evernym.verity.actor.cluster_singleton.SingletonParent
import com.evernym.verity.actor.metrics.{CollectionsMetricCollector, LibindyMetricsCollector}
import com.evernym.verity.actor.msg_tracer.MsgTracingRegionActors
import com.evernym.verity.actor.node_singleton.NodeSingleton
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageTracker
import com.evernym.verity.actor.segmentedstates.SegmentedStateStore
import com.evernym.verity.actor.url_mapper.UrlStore
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.container.actor.ActorProtocol
import com.evernym.verity.util.TimeZoneUtil.UTCZoneId
import com.evernym.verity.util.Util._

import java.time.ZoneId
import java.util.concurrent.TimeUnit
import com.evernym.verity.actor.appStateManager.{AppStateManager, SDNotifyService, SysServiceNotifier, SysShutdownProvider, SysShutdownService}
import com.evernym.verity.actor.metrics.activity_tracker.ActivityTracker
import com.evernym.verity.actor.resourceusagethrottling.helper.UsageViolationActionExecutor
import com.evernym.verity.actor.typed.base.UserGuardian
import com.evernym.verity.eventing.adapters.basic.event_store.BasicEventStoreAPI
import com.evernym.verity.eventing.adapters.kafka.consumer.{ConsumerSettingsProvider, KafkaConsumerAdapter}
import com.evernym.verity.eventing.event_handlers.ConsumedMessageHandler
import com.evernym.verity.eventing.ports.consumer.ConsumerPort
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.vdrtools.Libraries
import com.evernym.verity.util.healthcheck.HealthChecker
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
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

  val nodeSingleton: ActorRef = agentActorContext.system.actorOf(NodeSingleton.props(appConfig,
    executionContextProvider.futureExecutionContext), name = "node-singleton")

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
    ),
    passivateIdleEntityAfter = Some(FiniteDuration(
      ConfigUtil.getReceiveTimeout(
        appConfig,
        AgencyAgent.defaultPassivationTimeout,
        PERSISTENT_ACTOR_BASE,
        AGENCY_AGENT_REGION_ACTOR_NAME,
        null
      ).toSeconds,
      TimeUnit.SECONDS
    ))
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
    ),
    passivateIdleEntityAfter = Some(FiniteDuration(
      ConfigUtil.getReceiveTimeout(
        appConfig,
        AgencyAgentPairwise.defaultPassivationTimeout,
        PERSISTENT_ACTOR_BASE,
        AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
        null
      ).toSeconds,
      TimeUnit.SECONDS
    ))
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
    ),
    passivateIdleEntityAfter = Some(FiniteDuration(
      ConfigUtil.getReceiveTimeout(
        appConfig,
        UserAgent.defaultPassivationTimeout,
        PERSISTENT_ACTOR_BASE,
        USER_AGENT_REGION_ACTOR_NAME,
        null
      ).toSeconds,
      TimeUnit.SECONDS
    ))
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
    ),
    passivateIdleEntityAfter = Some(FiniteDuration(
      ConfigUtil.getReceiveTimeout(
        appConfig,
        UserAgentPairwise.defaultPassivationTimeout,
        PERSISTENT_ACTOR_BASE,
        USER_AGENT_PAIRWISE_REGION_ACTOR_NAME,
        null
      ).toSeconds,
      TimeUnit.SECONDS
    ))
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
    ),
    passivateIdleEntityAfter = Some(FiniteDuration(
      ConfigUtil.getReceiveTimeout(
        appConfig,
        ActivityTracker.defaultPassivationTimeout,
        PERSISTENT_ACTOR_BASE,
        ACTIVITY_TRACKER_REGION_ACTOR_NAME,
        null
      ).toSeconds,
      TimeUnit.SECONDS
    ))
  )

  //token manager
  val tokenToActorItemMapperRegion: ActorRef = createPersistentRegion(
    TOKEN_TO_ACTOR_ITEM_MAPPER_REGION_ACTOR_NAME,
    TokenToActorItemMapper.props(executionContextProvider.futureExecutionContext)(agentActorContext.appConfig),
    forTokenShardIdExtractor,
    forTokenEntityIdExtractor,
    Some(FiniteDuration(
      ConfigUtil.getReceiveTimeout(
        appConfig,
        TokenToActorItemMapper.defaultPassivationTimeout,
        PERSISTENT_ACTOR_BASE,
        TOKEN_TO_ACTOR_ITEM_MAPPER_REGION_ACTOR_NAME,
        null
      ).toSeconds,
      TimeUnit.SECONDS
    ))
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
    forUrlMapperEntityIdExtractor,
    passivateIdleEntityAfter = Some(FiniteDuration(
      ConfigUtil.getReceiveTimeout(
        appConfig,
        UrlStore.defaultPassivationTimeout,
        PERSISTENT_ACTOR_BASE,
        URL_STORE_REGION_ACTOR_NAME,
        null
      ).toSeconds,
      TimeUnit.SECONDS
    ))
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
      ResourceUsageTracker.props(agentActorContext.appConfig, actionExecutor, executionContextProvider.futureExecutionContext),
      passivateIdleEntityAfter = Some(FiniteDuration(
        ConfigUtil.getReceiveTimeout(
          appConfig,
          ResourceUsageTracker.defaultPassivationTimeout,
          PERSISTENT_ACTOR_BASE,
          RESOURCE_USAGE_TRACKER_REGION_ACTOR_NAME,
          null
        ).toSeconds,
        TimeUnit.SECONDS
      ))
    )
  }

  //other region actors

  val routeRegion: ActorRef =
    createPersistentRegion(
      ROUTE_REGION_ACTOR_NAME,
      Route.props(executionContextProvider.futureExecutionContext),
      passivateIdleEntityAfter = Some(FiniteDuration(
        ConfigUtil.getReceiveTimeout(
          appConfig,
          Route.defaultPassivationTimeout,
          PERSISTENT_ACTOR_BASE,
          ROUTE_REGION_ACTOR_NAME,
          null
        ).toSeconds,
        TimeUnit.SECONDS
      ))
    )

  // protocol region actors
  val protocolRegions: Map[String, ActorRef] = agentActorContext.protocolRegistry
    .entries
    .map { e =>
      val ap = ActorProtocol(e.protoDef)
      val region = createProtoActorRegion(
        ap.typeName,
        ap.props(agentActorContext, executionContextProvider.futureExecutionContext),
        passivateIdleEntityAfter = Some(FiniteDuration(
          ConfigUtil.getReceiveTimeout(
            appConfig,
            ActorProtocol.defaultPassivationTimeout,
            ActorProtocol.entityCategory,
            ap.typeName,
            null
          ).toSeconds,
          TimeUnit.SECONDS
        ))
      )
      ap.typeName -> region
    }.toMap


  //segmented state region actors
  val segmentedStateRegions: Map[String, ActorRef] = agentActorContext.protocolRegistry
    .entries.filter(_.protoDef.segmentStoreStrategy.isDefined)
    .map { e =>
      val typeName = SegmentedStateStore.buildTypeName(e.protoDef.protoRef)
      val region = createPersistentRegion(
        typeName,
        SegmentedStateStore.props(agentActorContext.appConfig, executionContextProvider.futureExecutionContext),
        passivateIdleEntityAfter = Some(FiniteDuration(
          ConfigUtil.getReceiveTimeout(
            appConfig,
            SegmentedStateStore.defaultPassivationTimeout,
            PERSISTENT_ACTOR_BASE,
            typeName,
            null
          ).toSeconds,
          TimeUnit.SECONDS
        ))
      )
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

  val appStateCoordinator = new AppStateCoordinator(
    appConfig,
    actorSystem,
    this)(agentActorContext.futureExecutionContext)

  val basicEventStore: Option[BasicEventStoreAPI] = if (appConfig.getStringReq(EVENT_SOURCE) == "verity.eventing.basic-source") {
    Option(new BasicEventStoreAPI(appConfig.config)(actorSystem, executionContextProvider.futureExecutionContext))
  } else None

  lazy val isVAS: Boolean =
    appConfig
      .getStringOption(AKKA_SHARDING_REGION_NAME_USER_AGENT)
      .contains("VerityAgent")

  val logger: Logger = getLoggerByClass(getClass)

  //should be lazy and only used/created during startup process (post dependency check)
  lazy val eventConsumerAdapter: Option[ConsumerPort] = {
    if (isVAS) {
      val configPath = appConfig.getStringReq(EVENT_SOURCE)
      val clazz = appConfig.getStringReq(s"$configPath.builder-class")
      Option(
        Class
          .forName(clazz)
          .getConstructor()
          .newInstance()
          .asInstanceOf[EventConsumerAdapterBuilder]
          .build(appConfig, agentActorContext.agentMsgRouter, singletonParentProxy, executionContextProvider.futureExecutionContext, actorSystem)
      )
    } else None
  }
}

trait EventConsumerAdapterBuilder {
  def build(appConfig: AppConfig,
            agentMsgRouter: AgentMsgRouter,
            singletonParentProxy: ActorRef,
            executionContext: ExecutionContext,
            actorSystem: ActorSystem): ConsumerPort
}


class KafkaEventConsumerAdapterBuilder
  extends EventConsumerAdapterBuilder {

  override def build(appConfig: AppConfig,
                     agentMsgRouter: AgentMsgRouter,
                     singletonParentProxy: ActorRef,
                     executionContext: ExecutionContext,
                     actorSystem: ActorSystem): ConsumerPort = {
    import akka.actor.typed.scaladsl.adapter._
    val consumerSettingsProvider = ConsumerSettingsProvider(appConfig.config)
    new KafkaConsumerAdapter(
      new ConsumedMessageHandler(
        appConfig.config,
        agentMsgRouter, singletonParentProxy)(executionContext),
      consumerSettingsProvider)(executionContext, actorSystem.toTyped)
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
