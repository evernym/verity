package com.evernym.verity.integration.base

import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.integration.base.verity_provider.node.VerityNode
import com.evernym.verity.integration.base.verity_provider.{PortProfile, SharedEventStore, VerityEnv}
import com.evernym.verity.integration.base.verity_provider.node.local.{ServiceParam, VerityLocalNode}
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.{Files, Path}
import scala.language.postfixOps
import scala.util.Random


//TODO: below are list of known (there might be more) "global singleton objects"
// which may/will cause issues sooner or later
// if try to use multi node cluster in single JVM (like what this VerityProviderBaseSpec does)
//    1. AppConfigWrapper
//    2. ResourceBlockingStatusMngrCache
//    3. ResourceWarningStatusMngrCache
//    4. MsgProgressTrackerCache
//    5. MetricsReader and KamonPrometheusMetricsReporter
//    6. ItemConfigManager
//    7. AppStateUpdateAPI

/**
 * base class for specs to use LocalVerity
 */
trait VerityProviderBaseSpec
  extends BasicSpec
    with CancelGloballyAfterFailure
    with BeforeAndAfterAll {
    this: Suite =>

  object VerityEnvBuilder {

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

    def build(): VerityEnv = {
      val tmpDir = randomTmpDirPath()
      val totalNodeCount = nodeCount
      val multiNodeServiceParam = if (totalNodeCount > 1) {
        //if cluster is being created for more than one node and because we are using 'leveldb'
        // for journal which is not usable by multiple actor system at the same time (because of file locking etc)
        // hence to overcome that we need to use "shared event store"
        serviceParam.map(_.copy(sharedEventStore = Option(new SharedEventStore(tmpDir))))
      } else serviceParam

      //adding fallback common config (unless overridden) to be needed for multi node testing
      val multiNodeClusterConfig = buildMultiNodeClusterConfig(overriddenConfig)

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
          multiNodeClusterConfig
        )
      }

      if (verityNodes.isEmpty) throw new RuntimeException("at least one node needed for a verity environment")
      val verityEnv = VerityEnv(appSeed, verityNodes)
      allVerityEnvs = allVerityEnvs :+ verityEnv
      verityEnv
    }
  }

  //default service param to be used for all verity instances
  // implementing class can override it or send specific one for specific verity instance as well
  // but for external storage type of services (like ledger) we should make sure
  // it is the same instance across the all verity environments
  lazy val defaultSvcParam: ServiceParam = ServiceParam.empty.withLedgerTxnExecutor(new MockLedgerTxnExecutor())

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
    allVerityEnvs.foreach(_.nodes.foreach(_.stop()))
  }

  private def randomChar(): Char = {
    val high = 57
    val low = 48
    (Random.nextInt(high - low) + low).toChar
  }

  private def buildMultiNodeClusterConfig(overriddenConfig: Option[Config] = None): Option[Config] = {
    Option(
      overriddenConfig match {
        case Some(c)  => c.withFallback(MULTI_NODE_CLUSTER_CONFIG)
        case None     => MULTI_NODE_CLUSTER_CONFIG
      }
    )
  }

  private val MULTI_NODE_CLUSTER_CONFIG = ConfigFactory.parseString(
    s"""
      |verity.app-state-manager.state.draining {
      |  delay-before-leave = 5
      |  delay-between-status-checks = 1
      |  max-status-check-count = 20
      |}
      |""".stripMargin
  )
}
