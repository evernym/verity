package com.evernym.verity.integration.base

import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import akka.util.Timeout
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetStoredRoute}
import com.evernym.verity.actor.base.Stop
import com.evernym.verity.actor.maintenance.v1tov2migration.EventPersister
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.actor.wallet.WalletCommand
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.config.ConfigConstants.TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS
import com.evernym.verity.constants.ActorNameConstants
import com.evernym.verity.constants.ActorNameConstants.ROUTE_REGION_ACTOR_NAME
import com.evernym.verity.constants.Constants.DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.integration.base.verity_provider.node.VerityNode
import com.evernym.verity.integration.base.verity_provider.node.local.{ServiceParam, VerityLocalNode}
import com.evernym.verity.integration.base.verity_provider.{PortProfile, SharedEventStore, VerityEnv}
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerTxnExecutor}
import com.evernym.verity.msgoutbox.WalletId
import com.evernym.verity.observability.logs.LoggingUtil
import com.evernym.verity.protocol.container.actor.ActorProtocol
import com.evernym.verity.protocol.engine.events.{DataRetentionPolicySet, DomainIdSet, StorageIdSet}
import com.evernym.verity.protocol.engine.{DomainId, MockVDRAdapter, PinstId, ProtoDef, RelationshipId, ThreadId}
import com.evernym.verity.protocol.protocols.protocolRegistry
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.evernym.verity.util.Util.buildTimeout
import com.evernym.verity.util2.{ExecutionContextProvider, HasExecutionContextProvider, RouteId}
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.service.ActorWalletService
import com.evernym.verity.vault.wallet_api.StandardWalletAPI
import com.evernym.verity.vdr.base.INDY_SOVRIN_NAMESPACE
import com.evernym.verity.vdr.service.VdrTools
import com.evernym.verity.vdr.{MockIndyLedger, MockLedgerRegistryBuilder, MockVdrTools}
import com.evernym.verity.vdrtools.ledger.IndyLedgerPoolConnManager
import com.typesafe.config.{Config, ConfigFactory, ConfigMergeable}
import com.typesafe.scalalogging.Logger
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.{Files, Path}
import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Random

/**
 * base class for specs to use LocalVerity
 */
trait VerityProviderBaseSpec
  extends BasicSpec
    with CancelGloballyAfterFailure
    with BeforeAndAfterAll
    with HasExecutionContextProvider {
    this: Suite =>


  val ENV_BUILD_TIMEOUT = 1 minute
  val SDK_BUILD_TIMEOUT = 1 minute

  implicit def contextClassLoader: ClassLoader = Thread.currentThread().getContextClassLoader


  object VerityEnvBuilder {
    val localVerityBaseConfig: ConfigMergeable = ConfigFactory.load()

    def empty: VerityEnvBuilder = VerityEnvBuilder(0, None, None)

    def default(nodeCount: Int = 1): VerityEnvBuilder =
      VerityEnvBuilder(nodeCount, Option(defaultSvcParam))
  }

  case class RemoteVerityNodeParam(count: Int, fromJarPath: String)

  case class VerityEnvBuilder(nodeCount: Int,
                              serviceParam: Option[ServiceParam] = None,
                              overriddenConfig: Option[Config] = None) {


    def withServiceParam(param: ServiceParam): VerityEnvBuilder = copy(serviceParam = Option(param))
    def withConfig(config: Config): VerityEnvBuilder = copy(overriddenConfig = Option(config))
    def withLedgerTxnExecutor(ledgerTxnExecutor: LedgerTxnExecutor): VerityEnvBuilder = {
      val newServiceParam = serviceParam.getOrElse(defaultSvcParam).withLedgerTxnExecutor(ledgerTxnExecutor)
      copy(serviceParam = Option(newServiceParam))
    }
    def withVdrTools(vdrTools: VdrTools): VerityEnvBuilder = {
      val newServiceParam = serviceParam.getOrElse(defaultSvcParam).withVdrTools(vdrTools)
      copy(serviceParam = Option(newServiceParam))
    }

    private val logger: Logger = LoggingUtil.getLoggerByName("VerityEnvBuilder")

    def buildAsync(appType: AppType)(implicit ec: ExecutionContext = futureExecutionContext): Future[VerityEnv] = {
      Future {
        createNodes(appType)
      } flatMap {
        nodes =>
        if (nodes.isEmpty) throw new RuntimeException("at least one node needed for a verity environment")
        createEnvAsync(nodes, nodes.head.appSeed)
      } recover {
        case e: Throwable => throw e
      }
    }

    def build(appType: AppType): VerityEnv = {
      val nodes = createNodes(appType)
      if (nodes.isEmpty) throw new RuntimeException("at least one node needed for a verity environment")
      createEnv(nodes, nodes.head.appSeed)
    }

    private def createNodes(appType: AppType): Seq[VerityNode] = {
      val tmpDir = randomTmpDirPath()
      val totalNodeCount = nodeCount
      val multiNodeServiceParam = if (totalNodeCount > 1) {
        //if cluster is being created for more than one node and because we are using 'leveldb'
        // for journal which is not usable by multiple actor system at the same time (because of file locking etc)
        // hence to overcome that we need to use "shared event store"
        serviceParam.map(_.copy(sharedEventStore = Option(new SharedEventStore(tmpDir))))
      } else serviceParam

      //adding fallback common config (unless overridden) to be needed for multi node testing
      val multiNodeClusterConfig = buildVerityAppConfig(appType, overriddenConfig)

      val appSeed = (0 to 31).map(_ => randomChar()).mkString("")
      val portProfiles = (1 to totalNodeCount)
        .map( _ => PortProfile.random())
        .sortBy(_.artery)

      val arteryPorts = portProfiles.map(_.artery)

      val verityNodesPortProfiles = portProfiles.take(nodeCount)

      val verityNodes: Seq[VerityNode] = verityNodesPortProfiles.map { portProfile =>
        val otherNodesArteryPorts = arteryPorts.filterNot(_ == portProfile.artery)
        VerityLocalNode(
          tmpDir,
          appSeed,
          portProfile,
          otherNodesArteryPorts,
          multiNodeServiceParam,
          multiNodeClusterConfig,
          executionContextProvider,
          VerityEnvBuilder.localVerityBaseConfig
        )
      }
      verityNodes
    }

    private def createEnv(verityNodes: Seq[VerityNode], appSeed: String): VerityEnv = {
      implicit val ec = futureExecutionContext
      logger.info(s"Start verity nodes with ports ${verityNodes.map(_.portProfile.artery)}")
      try {
        startVerityNodes(verityNodes, VerityEnv.START_MAX_TIMEOUT)
        val verityEnv = VerityEnv(appSeed, verityNodes, futureExecutionContext)
        allVerityEnvs = allVerityEnvs :+ verityEnv
        verityEnv
      }
      catch {
        case e: Exception =>
          logger.warn(s"Verity nodes ${verityNodes.map(_.portProfile.artery)} start failed. ", e)
          Await.result(Future.sequence(verityNodes.map(_.stop())), VerityEnv.STOP_MAX_TIMEOUT)
          throw e
      }
    }

    private def createEnvAsync(verityNodes: Seq[VerityNode], appSeed: String): Future[VerityEnv] = {
      implicit val ec: ExecutionContext = futureExecutionContext
      logger.info(s"Start verity nodes with ports ${verityNodes.map(_.portProfile.artery)}")
      try {
        startVerityNodesAsync(verityNodes, VerityEnv.START_MAX_TIMEOUT) map { _ =>
          val verityEnv = VerityEnv(appSeed, verityNodes, futureExecutionContext)
          allVerityEnvs = allVerityEnvs :+ verityEnv
          verityEnv
        }
      }
      catch {
        case e: Exception =>
          logger.warn(s"Verity nodes ${verityNodes.map(_.portProfile.artery)} start failed. ", e)
          Await.result(Future.sequence(verityNodes.map(_.stop())), VerityEnv.STOP_MAX_TIMEOUT)
          throw e
      }
    }

    private def startVerityNodes(verityNodes: Seq[VerityNode], maxStartTimeout: FiniteDuration)(implicit ec: ExecutionContext): Unit = {
      val otherNodes = verityNodes.drop(1)
      Await.result(verityNodes.head.start(), maxStartTimeout)
      Await.result(Future.sequence(otherNodes.map(_.start())), maxStartTimeout)
      logger.info(s" Verity nodes with ports ${verityNodes.map(_.portProfile.artery)} started")
    }

    private def startVerityNodesAsync(verityNodes: Seq[VerityNode], maxStartTimeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Seq[Unit]] = {
      val otherNodes = verityNodes.drop(1)
      Await.result(verityNodes.head.start(), maxStartTimeout)
      Future.sequence(otherNodes.map(_.start()))
    }

  }

  //default service param to be used for all verity instances
  // implementing class can override it or send specific one for specific verity instance as well
  // but for external storage type of services (like ledger) we should make sure
  // it is the same instance across the all verity environments
  lazy val defaultSvcParam: ServiceParam = {
    val testAppConfig = new TestAppConfig()
    val vdrTools = new MockVdrTools(
      MockLedgerRegistryBuilder()
        .withLedger(INDY_SOVRIN_NAMESPACE, MockIndyLedger("genesis.txn file path", None))
        .build())(futureExecutionContext)
    val vdrToolsAdapter = new MockVDRAdapter(vdrTools)(futureExecutionContext)
    val ledgerTxnExecutor = new MockLedgerTxnExecutor(futureExecutionContext, testAppConfig, vdrToolsAdapter)
    ServiceParam
      .empty
      .withLedgerTxnExecutor(ledgerTxnExecutor)
      .withVdrTools(vdrTools)
  }


  private def randomTmpDirPath(): Path = {
    val tmpDir = TempDir.findSuiteTempDir(this.suiteName)
    Files.createTempDirectory(tmpDir, s"local-verity-").toAbsolutePath
  }

  /**
   * list of verity environments created by implementing class,
   * to be teared down at the end of the spec
   * @return
   */
  private var allVerityEnvs: Seq[VerityEnv] = Seq.empty

  override def afterAll(): Unit = {
    super.afterAll()
    implicit val ec = futureExecutionContext
    val future = Future.sequence(
      allVerityEnvs.flatMap(e => e.nodes).map(_.stop())
    )
    Await.result(future, VerityEnv.STOP_MAX_TIMEOUT)
  }

  private def randomChar(): Char = {
    val high = 57
    val low = 48
    (Random.nextInt(high - low) + low).toChar
  }

  private def buildVerityAppConfig(appType: AppType,
                                   overriddenConfig: Option[Config] = None): Option[Config] = {

    val appTypeConfig = MULTI_NODE_CLUSTER_CONFIG.withFallback {
      appType match {
        case EAS => EAS_DEFAULT_CONFIG
        case CAS => CAS_DEFAULT_CONFIG
        case VAS => VAS_DEFAULT_CONFIG
      }
    }

    Option(
      overriddenConfig match {
        case Some(c)  => c.withFallback(appTypeConfig)
        case None     => appTypeConfig
      }
    )
  }

  protected def performWalletOp[T](verityEnv: VerityEnv,
                                   walletId: WalletId,
                                   cmd: WalletCommand): T = {
    val verityNode = verityEnv.nodes.head.asInstanceOf[VerityLocalNode]
    val actorSystem = verityNode.platform.actorSystem
    val appConfig = verityNode.platform.appConfig
    val ledgerPoolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(actorSystem, appConfig, futureExecutionContext)
    val walletAPI = new StandardWalletAPI(
      new ActorWalletService(
        actorSystem,
        appConfig,
        ledgerPoolConnManager,
        futureExecutionContext
      )
    )
    implicit val wap: WalletAPIParam = WalletAPIParam(walletId)
    walletAPI.executeSync[T](cmd)
  }

  protected def getAgentRoute(verityEnv: VerityEnv,
                              routeId: RouteId): ActorAddressDetail = {
    import akka.pattern.ask
    val verityNode = verityEnv.nodes.head.asInstanceOf[VerityLocalNode]
    val actorSystem = verityNode.platform.actorSystem
    val appConfig = verityNode.platform.appConfig
    val routeRegion = ClusterSharding(actorSystem).shardRegion(ROUTE_REGION_ACTOR_NAME)
    implicit lazy val timeout: Timeout = buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS,
      DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)
    Await.result(routeRegion.ask(ForIdentifier(routeId, GetStoredRoute)).mapTo[Option[ActorAddressDetail]], 10.second).get
  }

  protected def persistProtocolEvents(verityEnv: VerityEnv,
                                      protoDef: ProtoDef,
                                      domainId: DomainId,
                                      relationshipId: Option[RelationshipId],
                                      threadId: Option[ThreadId],
                                      events: Seq[Any],
                                      stopActor: Boolean = true): Unit = {
    val pinstIdResolver = protocolRegistry.find(protoDef.protoRef).get.pinstIdResol
    val pinstId = pinstIdResolver.resolve(
      protoDef,
      domainId,
      relationshipId,
      threadId,
      None,
      None
    )
    val actorProtocol = new ActorProtocol(protoDef)
    val verityNode = verityEnv.nodes.head.asInstanceOf[VerityLocalNode]
    val internalEvents = buildDefaultProtoSysEvents(verityNode.platform.appConfig, protoDef, domainId, pinstId)
    persistEvents(
      verityNode,
      actorProtocol.typeName,
      pinstId,
      internalEvents  ++ events,
      None,
      stopActor
    )
  }

  protected def persistUserAgentEvents(verityEnv: VerityEnv,
                                       entityId: EntityId,
                                       events: Seq[Any],
                                       stopActor: Boolean = true): Unit = {
    persistActorEvents(
      verityEnv,
      ActorNameConstants.USER_AGENT_REGION_ACTOR_NAME,
      entityId,
      events,
      stopActor
    )
  }

  private def persistActorEvents(verityEnv: VerityEnv,
                                 actorTypeName: String,
                                 entityId: EntityId,
                                 events: Seq[Any],
                                 stopActor: Boolean = true): Unit = {
    val verityNode = verityEnv.nodes.head.asInstanceOf[VerityLocalNode]
    persistEvents(
      verityNode,
      actorTypeName,
      entityId,
      events,
      None,
      stopActor
    )
  }

  private def persistEvents(verityNode: VerityLocalNode,
                            actorTypeName: String,
                            entityId: String,
                            events: Seq[Any],
                            persEncryptionKey: Option[String],
                            stopActor: Boolean = true): Unit = {
    val verityNodeActorSystem = verityNode.platform.actorSystem
    val eventPersisterActorName = UUID.randomUUID().toString
    verityNodeActorSystem.actorOf(
      EventPersister.props(
        verityNode.platform.appConfig,
        futureExecutionContext,
        actorTypeName,
        entityId,
        persEncryptionKey,
        events
      ),
      eventPersisterActorName
    )
    val targetRegion = ClusterSharding(verityNodeActorSystem).shardRegion(actorTypeName)
    if (stopActor) {
      //stop the target actor to make sure this actor starts from scratch (if needed)
      // which will force the actor to replay all the persisted events
      targetRegion ! ForIdentifier(entityId, Stop())
    }
    Thread.sleep(5000) //TODO: how to know if the `eventPersister` is stopped?
  }

  private def buildDefaultProtoSysEvents(appConfig: AppConfig,
                                         protoDef: ProtoDef,
                                         domainId: DomainId,
                                         pinstId: PinstId): Seq[Any] = {
    Seq(
      DomainIdSet(domainId),
      StorageIdSet(pinstId),
      DataRetentionPolicySet(ConfigUtil.getProtoStateRetentionPolicy(appConfig, domainId, protoDef.protoRef.msgFamilyName).configString)
    )
  }


  private val VAS_DEFAULT_CONFIG = ConfigFactory.parseString(
    """
      |akka {
      |  sharding-region-name {
      |    user-agent = "VerityAgent"
      |    user-agent-pairwise = "VerityAgentPairwise"
      |  }
      |}
      |""".stripMargin
  )

  private val CAS_DEFAULT_CONFIG = ConfigFactory.parseString(
    """
      |akka {
      |  sharding-region-name {
      |    user-agent = "ConsumerAgent"
      |    user-agent-pairwise = "ConsumerAgentPairwise"
      |  }
      |}
      |""".stripMargin
  )

  private val EAS_DEFAULT_CONFIG = ConfigFactory.parseString(
    """
      |akka {
      |  sharding-region-name {
      |    user-agent = "EnterpriseAgent"
      |    user-agent-pairwise = "EnterpriseAgentPairwise"
      |  }
      |}
      |""".stripMargin
  )

  private val MULTI_NODE_CLUSTER_CONFIG = ConfigFactory.parseString(
    s"""
      |
      |""".stripMargin
  )
  def executionContextProvider: ExecutionContextProvider
}

trait AppType
case object CAS extends AppType
case object VAS extends AppType
case object EAS extends AppType