package com.evernym.verity.integration.base

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.integration.base.verity_provider.node.VerityNode
import com.evernym.verity.integration.base.verity_provider.node.local.{ServiceParam, VerityLocalNode}
import com.evernym.verity.integration.base.verity_provider.{PortProfile, SharedEventStore, VerityEnv}
import com.evernym.verity.ledger.LedgerTxnExecutor
import com.evernym.verity.observability.logs.LoggingUtil
import com.evernym.verity.protocol.engine.MockVDRAdapter
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.evernym.verity.util2.{ExecutionContextProvider, HasExecutionContextProvider}
import com.evernym.verity.vdr.base.INDY_SOVRIN_NAMESPACE
import com.evernym.verity.vdr.service.VdrTools
import com.evernym.verity.vdr.{MockIndyLedger, MockLedgerRegistry, MockVdrTools}
import com.typesafe.config.{Config, ConfigFactory, ConfigMergeable}
import com.typesafe.scalalogging.Logger
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.{Files, Path}
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
    val vdrTools = new MockVdrTools(MockLedgerRegistry(
      List(MockIndyLedger(List(INDY_SOVRIN_NAMESPACE), "genesis.txn file path", None))))(futureExecutionContext)
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