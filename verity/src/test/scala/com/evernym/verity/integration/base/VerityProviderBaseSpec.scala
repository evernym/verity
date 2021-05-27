package com.evernym.verity.integration.base

import akka.actor.{ActorRef, ActorSystem, ExtendedActorSystem}
import akka.cluster.{Cluster, MemberStatus}
import akka.cluster.MemberStatus._
import akka.testkit.TestKit
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.node_singleton.DrainNode
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.integration.base.verity_provider.LocalVerity.atMost
import com.evernym.verity.integration.base.verity_provider.{LedgerSvcParam, LocalVerity, PortProfile, ServiceParam}
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.net.InetAddress
import java.nio.file.{Files, Path}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random


//TODO: below are list of known (there might be more) "global singleton objects"
// which may/will cause issues sooner or later
// if try to use multi node cluster in single JVM (like what this VerityProviderBaseSpec does)
//    1. AppConfigWrapper
//    2. ResourceBlockingStatusMngrCache
//    3. ResourceWarningStatusMngrCache
//    4. AppStateUpdateAPI


/**
 * base class for specs to use LocalVerity
 */
trait VerityProviderBaseSpec
  extends BasicSpec
    with CancelGloballyAfterFailure
    with BeforeAndAfterAll {
    this: Suite =>

  //default service param to be used for all verity instances
  // implementing class can override it or send specific one for specific verity instance as well
  // but for external storage type of services (like ledger) we should make sure
  // it is the same instance across the all verity environments
  val defaultSvcParam: ServiceParam = ServiceParam(LedgerSvcParam(ledgerTxnExecutor = new MockLedgerTxnExecutor()))

  def setupNewVerityEnv(nodeCount: Int = 1,
                        serviceParam: ServiceParam = defaultSvcParam,
                        overriddenConfig: Option[Config] = None): VerityEnv = {
    val tmpDir = randomTmpDirPath()

    val multiNodeServiceParam = if (nodeCount > 1) {
      //if more than one node has to be part of the cluster and
      // we are using 'leveldb' for journal which is not usable by multiple actor system at once
      // (because of file locking etc)
      // hence to overcome that we use shared event store
      serviceParam.copy(sharedEventStore = Option(new SharedEventStore(tmpDir)))
    } else serviceParam
    val multiNodeClusterConfig = buildMultiNodeClusterConfig(overriddenConfig)
    val appSeed = (0 to 31).map(_ => randomChar()).mkString("")
    val portProfiles = (1 to nodeCount)
      .map( _ => getRandomPortProfile)
      .sortBy(_.artery)

    val arteryPorts = portProfiles.map(_.artery)

    val verityNodes = portProfiles.map { portProfile =>
      val otherNodesArteryPorts = arteryPorts.filterNot(_ == portProfile.artery)
      VerityNode(
        tmpDir,
        appSeed,
        multiNodeServiceParam,
        portProfile,
        otherNodesArteryPorts,
        multiNodeClusterConfig
      )
    }
    val verityEnv = VerityEnv(appSeed, verityNodes)
    allVerityEnvs = allVerityEnvs :+ verityEnv
    verityEnv
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
  private var allVerityEnvs: List[VerityEnv] = List.empty

  private def getRandomPortProfile: PortProfile = {
    val randomPortProfiles = Stream.continually(PortProfile.random()).take(10)
    val availableProfile = randomPortProfiles find { portProfile ⇒
      val usedPortProfiles = allVerityEnvs.flatMap(_.nodes).map(_.thisNodePortProfile)
      if (! usedPortProfiles.contains(portProfile)) true else false
    } getOrElse sys.error(s"could not create unused port profile after 10 attempts")
    availableProfile
  }

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

case class VerityEnv(seed: String,
                     nodes: Seq[VerityNode]) {

  def availableNodes: Seq[VerityNode] = nodes.filter(_.isAvailable)

  def stopNodeAtIndex(index: Int): Unit = {
    stopNodeAtIndexes(List(index))
  }

  def stopNodeAtIndexes(indexes: List[Int]): Unit = {
    indexes.foreach(checkIndex)

    val (targetNodes, remainingNodes) = {
      val (targetNodes, otherNodes) =
        nodes.zipWithIndex.partition { case (_, index) => indexes.contains(index) }
      (targetNodes.map(_._1), otherNodes.filter(_._1.isAvailable).map(_._1))
    }

    targetNodes.foreach(_.stop())

    val nodesToBeChecked = remainingNodes.map { curNode =>
      val excludeArteryPorts = (targetNodes :+ curNode).map(_.thisNodePortProfile.artery)
      val otherNodes = remainingNodes.filter { n => ! excludeArteryPorts.contains(n.thisNodePortProfile.artery)}
      val otherNodeStatus: Map[VerityNode, List[MemberStatus]] =
        otherNodes.map(_ -> List(Up)).toMap ++ targetNodes.map(_ -> List(Removed, Down)).toMap
      (curNode, otherNodeStatus)
    }

    TestKit.awaitCond(nodesToBeChecked.forall(n => checkIfNodeIsUp(n._1, n._2)), atMost, 3.seconds)
  }

  /**
   * checks if given nodes are up and other node's status is also up for each of them
   *
   * @param targetNodes
   * @return
   */
  def checkIfNodesAreUp(targetNodes: Seq[VerityNode] = nodes): Boolean = {
    targetNodes.forall { tNode =>
      val otherNodesStatus = nodes
        .filterNot(_.thisNodePortProfile.http == tNode.thisNodePortProfile.http)
        .map(_ -> List(Up)).toMap
      checkIfNodeIsUp(tNode, otherNodesStatus)
    }
  }

  /**
   * check if given node is up and
   * it's cluster state to conform with otherNodesStatus (if given)
   *
   * @param node the node expected to be up
   * @param otherNodesStatus other nodes and their expected status
   * @return
   */
  private def checkIfNodeIsUp(node: VerityNode,
                              otherNodesStatus: Map[VerityNode, List[MemberStatus]] = Map.empty): Boolean = {
    require(! otherNodesStatus.contains(node), "node expected to be up can't be in otherNodesStatus")

    val cluster = Cluster(node.platform.actorSystem)
    cluster.selfMember.status == Up &&
      otherNodesStatus.forall { case (otherNode, expectedStatus) =>
        val otherMember = cluster.state.members.find(_.address.toString.contains(otherNode.thisNodePortProfile.artery.toString))
        otherMember match {
          case None if expectedStatus.contains(Down) || expectedStatus.contains(Removed) => true
          case None     => false
          case Some(om) => expectedStatus.contains(om.status)
        }
      }
  }

  private def checkIndex(index: Int): Unit = {
    require(index >=0 && index < nodes.size, s"invalid index: $index")
  }

  def startNodeAtIndex(index: Int): Unit = {
    nodes(index).start()
  }

  def restartNodeAtIndex(index: Int): Unit = {
    nodes(index).restart()
  }

  def stopAllNodes(): Unit = {
    nodes.foreach(_.stop())
  }

  def restartAllNodes(): Unit = {
    nodes.foreach(_.restart())
  }

  def init(): Unit = {
    nodes.head.bootstrapAgencyAgent()
  }

  init()
}

case class VerityNode(tmpDirPath: Path,
                      appSeed: String,
                      serviceParam: ServiceParam,
                      thisNodePortProfile: PortProfile,
                      otherNodeArteryPorts: Seq[Int],
                      overriddenConfig: Option[Config]) {

  var isAvailable: Boolean = false
  private var _httpServer: HttpServer = start()

  def httpServer: HttpServer = _httpServer

  def platform: Platform = httpServer.platform

  def restart():Unit = {
    stop()
    start()
  }

  def start(): HttpServer = {
    if (! isAvailable) {
      _httpServer = startVerityInstance(serviceParam)
      isAvailable = true
    }
    _httpServer
  }

  def stop(): Unit = {
    //TODO: at this stage, sometimes actor system logs 'java.lang.IllegalStateException: Pool shutdown unexpectedly' exception,
    // it doesn't impact the test in any way but should try to find and fix the root cause
    stopUngracefully()
  }

  //is this really ungraceful shutdown?
  private def stopUngracefully(): Unit = {
    isAvailable = false
    stopHttpServer()
    stopActorSystem()
  }

  private def stopHttpServer(): Unit = {
    val httpStopFut = httpServer.stop()
    Await.result(httpStopFut, 30.seconds)
  }

  private def stopActorSystem(): Unit = {
    val platformStopFut = platform.actorSystem.terminate()
    Await.result(platformStopFut, 30.seconds)
  }

  private def stopGracefully(): Unit = {
    //TODO: to find out why this one fails intermittently
    isAvailable = false
    val cluster = Cluster(platform.actorSystem)
    platform.nodeSingleton.tell(DrainNode, ActorRef.noSender)
    TestKit.awaitCond(isNodeShutdown(cluster), atMost, 3.seconds)
  }

  private def isNodeShutdown(cluster: Cluster): Boolean = {
    List(Removed, Down).contains(cluster.selfMember.status)
  }

  private def startVerityInstance(serviceParam: ServiceParam): HttpServer = {
    val httpServer = LocalVerity(tmpDirPath, appSeed, thisNodePortProfile, otherNodeArteryPorts, serviceParam,
      overriddenConfig=overriddenConfig, bootstrapApp = false)
    httpServer
  }

  def bootstrapAgencyAgent(): Unit = {
    LocalVerity.bootstrapApplication(thisNodePortProfile.http, appSeed)(httpServer.platform.actorSystem)
  }
}

case class VerityEnvUrlProvider(private val _nodes: Seq[VerityNode]) {
  def availableNodeUrls: Seq[String] = {
    _nodes.filter(_.isAvailable).map { np =>
      s"http://localhost:${np.thisNodePortProfile.http}"
    }
  }
}

/**
 * this class holds an actor system which is serving the event and snapshot storage
 * this is mainly useful when there is multi node cluster with file based journal (like level db)
 * which does posses locking challenges if all nodes try to use the same storage.
 *
 * NOTE: this shared event store may not be scalable/efficient,
 * so it's usage should be only for testing general scenarios not for any performance test.
 *
 * @param tempDir directory where journal and snapshot will be stored
 */
class SharedEventStore(tempDir: Path) {

  val arteryPort: Int = 2000 + Random.nextInt(900)  + Random.nextInt(90) + Random.nextInt(9)

  val actorSystem: ActorSystem = {
    val parts = Seq(
      sharedEventStoreConfig(),
      otherAkkaConfig(arteryPort)
    )
    val config = parts.fold(ConfigFactory.empty())(_.withFallback(_).resolve())
    ActorSystem("shared-event-store", config)
  }

  //address used by other nodes to point to this system as a journal/snapshot storage
  val address = actorSystem.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  def sharedEventStoreConfig(): Config = {
    val sharedDir = Files.createTempDirectory(tempDir, "shared-").toAbsolutePath
    ConfigFactory.parseString(
      s"""
         |akka.extensions = ["akka.persistence.journal.PersistencePluginProxyExtension"]
         |akka.persistence {
         |  journal.proxy.start-target-journal = on
         |  snapshot-store.proxy.start-target-snapshot-store = on
         |}
         |akka.persistence.journal {
         |  plugin = "akka.persistence.journal.proxy"
         |  proxy.target-journal-plugin = "akka.persistence.journal.leveldb"
         |  leveldb {
         |    dir = "$sharedDir"
         |    native = false
         |  }
         |}
         |akka.persistence.snapshot-store {
         |  plugin = "akka.persistence.snapshot-store.proxy"
         |  proxy.target-snapshot-store-plugin = "akka.persistence.snapshot-store.local"
         |  local = {
         |    dir = "${sharedDir.resolve("snapshots")}"
         |  }
         |}
         |""".stripMargin
    )
  }

  def otherAkkaConfig(port: Int): Config = {
    ConfigFactory.parseString(
      s"""
         |akka.actor.provider = cluster
         |akka.http.server.remote-address-header = on
         |akka.cluster.jmx.multi-mbeans-in-same-jvm = on
         |akka.remote.artery.canonical.hostname = ${InetAddress.getLocalHost.getHostAddress}
         |akka.remote.artery.canonical.port = $port
    """.stripMargin
    )
  }

}