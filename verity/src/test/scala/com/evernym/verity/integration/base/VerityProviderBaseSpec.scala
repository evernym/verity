package com.evernym.verity.integration.base

import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.integration.base.verity_provider.{LedgerSvcParam, LocalVerity, PortProfile, ServiceParam}
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.typesafe.config.Config
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.{Files, Path}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random


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
    val appSeed = randomChar().toString*32
    val portProfiles = (1 to nodeCount).map( _ => PortProfile.random()).zipWithIndex
    val verityNodes = portProfiles.map { case (portProfile, index) =>
      val otherNodesArteryPorts = portProfiles.filter(_._2 != index).map(_._1).map(_.artery).toList
      VerityNode(tmpDir, appSeed, serviceParam, portProfile, otherNodesArteryPorts, overriddenConfig)
    }.toList
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

  override def afterAll(): Unit = {
    super.afterAll()
    allVerityEnvs.foreach(_.nodes.foreach(_.stop()))
  }

  private def randomChar(): Char = {
    val high = 57
    val low = 48
    (Random.nextInt(high - low) + low).toChar
  }
}

//TODO: global singleton objects which may/will cause issues sooner or later
// if try to use multi node cluster in single JVM (like what this VerityEnv does)
//    1. AppConfigWrapper
//    2. ResourceBlockingStatusMngrCache
//    3. ResourceWarningStatusMngrCache
//    4. AppStateUpdateAPI

case class VerityEnv(seed: String,
                     nodes: List[VerityNode]) {

  def stopNode(httpPort: Int): Unit = {
    nodes.find(_.thisNodePortProfile.http == httpPort).foreach(_.stop())
  }

  def restartNode(httpPort: Int): Unit = {
    nodes.find(_.thisNodePortProfile.http == httpPort).foreach(_.restart())
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
                      otherNodeArteryPorts: List[Int],
                      overriddenConfig: Option[Config]) {

  var isAvailable: Boolean = false
  var _httpServer: HttpServer = startVerityInstance(serviceParam)

  def httpServer: HttpServer = _httpServer

  def platform: Platform = httpServer.platform

  def restart():Unit = {
    stop()
    start()
  }

  def start(): Unit = {
    _httpServer = startVerityInstance(serviceParam)
  }

  def stop(): Unit = {
    stopHttpServer()
    stopActorSystem()
    //TODO: at this stage, sometimes actor system logs 'java.lang.IllegalStateException: Pool shutdown unexpectedly' exception,
    // it doesn't impact the test in any way but should try to find and fix the root cause
  }

  private def stopHttpServer(): Unit = {
    val httpStopFut = httpServer.stop()
    Await.result(httpStopFut, 30.seconds)
  }

  private def stopActorSystem(): Unit = {
    val platformStopFut = platform.actorSystem.terminate()
    Await.result(platformStopFut, 30.seconds)
  }

  private def startVerityInstance(serviceParam: ServiceParam): HttpServer = {
    val httpServer = LocalVerity(tmpDirPath, appSeed, thisNodePortProfile, otherNodeArteryPorts, serviceParam,
      overriddenConfig=overriddenConfig, bootstrapApp = false)
    isAvailable = true
    httpServer
  }

  def bootstrapAgencyAgent(): Unit = {
    LocalVerity.bootstrapApplication(thisNodePortProfile.http, appSeed)(httpServer.platform.actorSystem)
  }
}

case class VerityEnvUrlProvider(private val _nodes: List[VerityNode]) {
  def availableNodeUrls: List[String] = {
    _nodes.filter(_.isAvailable).map { np =>
      s"http://localhost:${np.thisNodePortProfile.http}"
    }
  }
}