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
  val defaultSvcParam: ServiceParam = ServiceParam(LedgerSvcParam(ledgerTxnExecutor = new MockLedgerTxnExecutor()))

  def setupNewVerityApp(svcParam: ServiceParam = defaultSvcParam,
                        portProfile: PortProfile = PortProfile.random(),
                        overriddenConfig: Option[Config] = None): VerityRuntimeEnv = {
    val param = VerityAppParam(randomChar().toString*32, portProfile, randomTmpDirPath, svcParam, overriddenConfig)
    val vre = VerityRuntimeEnv(param)
    allVerityApps = allVerityApps :+ vre
    vre
  }

  private def randomTmpDirPath: Path = {
    val tmpDir = TempDir.findSuiteTempDir(this.suiteName)
    Files.createTempDirectory(tmpDir, "local-verity").toAbsolutePath
  }

  /**
   * list of verity app created by implementing class
   * to be teared down at the end of the spec
   * @return
   */
  private var allVerityApps: List[VerityRuntimeEnv] = List.empty

  override def afterAll(): Unit = {
    super.afterAll()
    allVerityApps.foreach(_.stop())
  }

  private def randomChar(): Char = {
    val high = 57
    val low = 48
    (Random.nextInt(high - low) + low).toChar
  }
}

case class VerityRuntimeEnv(param: VerityAppParam) {

  private var _httpServer = startVerityInstance(param.serviceParam)

  def httpServer: HttpServer = _httpServer

  def platform: Platform = httpServer.platform

  def restart():Unit = {
    stop()
    _httpServer = startVerityInstance(param.serviceParam, bootStrapApp = false)
  }

  def stop(): Unit = {
    stopHttpServer()
    stopActorSystem()
    //TODO: at this stage, sometimes actor system logs 'java.lang.IllegalStateException: Pool shutdown unexpectedly' exception,
    // it doesn't impact the test in any way but should try to find and fix the root cause
  }

  private def stopHttpServer(): Unit = {
    val httpStopFut = httpServer.stop()
    Await.result(httpStopFut, 15.seconds)
  }

  private def stopActorSystem(): Unit = {
    val platformStopFut = platform.actorSystem.terminate()
    Await.result(platformStopFut, 15.seconds)
  }

  private def startVerityInstance(serviceParam: ServiceParam, bootStrapApp: Boolean = true): HttpServer = {
    LocalVerity(param.randomTmpDirPath, param.portProfile, param.seed, serviceParam,
      bootstrapApp = bootStrapApp, overriddenConfig=param.overriddenConfig)
  }
}

case class VerityAppParam(seed: String,
                          portProfile: PortProfile,
                          randomTmpDirPath: Path,
                          serviceParam: ServiceParam,
                          overriddenConfig: Option[Config])