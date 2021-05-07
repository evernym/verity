package com.evernym.verity.integration.base

import com.evernym.verity.actor.Platform
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.integration.base.verity_provider.{LocalVerity, PortProfile}
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}
import com.typesafe.config.Config
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.{Files, Path}
import scala.language.postfixOps
import scala.util.Random


/**
 * base class for specs to use LocalVerity
 */
trait VerityAppBaseSpec
  extends BasicSpec
    with CancelGloballyAfterFailure
    with BeforeAndAfterAll {
    this: Suite =>

  def randomChar(): Char = {
    val high = 57
    val low = 48
    (Random.nextInt(high - low) + low).toChar
  }

  def setupNewVerityApp(portProfile: PortProfile = PortProfile.random(),
                        seed: String = randomChar().toString*32,
                        overriddenConfig: Option[Config] = None): HttpServer = {
    LocalVerity(randomTmpDirPath, portProfile, seed, overriddenConfig = overriddenConfig)
  }

  private def randomTmpDirPath: Path = {
    val tmpDir = TempDir.findSuiteTempDir(this.suiteName)
    Files.createTempDirectory(tmpDir, "local-verity").toAbsolutePath
  }

  /**
   * expects list of verity app created by implementing class
   * to be teared down at the end of the spec
   * @return
   */
  def allVerityApps: List[HttpServer]

  override def afterAll(): Unit = {
    super.afterAll()
    allVerityApps.map(_.stop())
  }
}

object LocalVerityUtil {

  def platformBaseUrl(platform: Platform): String = {
    val httpPort = platform.appConfig.getConfigIntReq("verity.http.port")
    s"http://localhost:$httpPort"
  }

  def platformAgencyUrl(platform: Platform): String = {
    s"${platformBaseUrl(platform)}/agency"
  }

  def platformAgencyMsgUrl(platform: Platform): String = {
    s"${platformAgencyUrl(platform)}/msg"
  }

  def platformAgencyRestApiUrl(platform: Platform): String = {
    s"${platformBaseUrl(platform)}/api"
  }
}