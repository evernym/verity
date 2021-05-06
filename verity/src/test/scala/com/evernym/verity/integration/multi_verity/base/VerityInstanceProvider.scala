package com.evernym.verity.integration.multi_verity.base

import com.evernym.verity.actor.Platform
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.integration.{LocalVerity, PortProfile}
import org.scalatest.Suite

import java.nio.file.{Files, Path}
import scala.language.postfixOps

trait VerityInstanceProvider {
  this: Suite =>

  def setupVerity(portProfile: PortProfile, seed: String): Platform =
    LocalVerity(randomTmpDirPath, portProfile, seed)

  private def randomTmpDirPath: Path = {
    val tmpDir = TempDir.findSuiteTempDir(this.suiteName)
    Files.createTempDirectory(tmpDir, "local-verity").toAbsolutePath
  }
}

object LocalVerityUtil {

  def platformAgencyUrl(platform: Platform): String = {
    val httpPort = platform.appConfig.getConfigIntReq("verity.http.port")
    s"http://localhost:$httpPort/agency"
  }

  def platformAgencyMsgUrl(platform: Platform): String = {
    s"${platformAgencyUrl(platform)}/msg"
  }
}