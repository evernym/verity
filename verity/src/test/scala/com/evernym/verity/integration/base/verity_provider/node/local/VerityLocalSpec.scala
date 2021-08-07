package com.evernym.verity.integration.base.verity_provider.node.local

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.http.base.AgentReqBuilder
import com.evernym.verity.integration.base.verity_provider.PortProfile
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider
import com.typesafe.config.Config


class VerityLocalSpec extends BasicSpec with AgentReqBuilder with CommonSpecUtil with TempDir {

  lazy val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp

  val defaultPorts: PortProfile = PortProfile(9002, 2552, 8552, 9095)

  override def deleteFiles: Boolean = false

  "LocalConfig" - {
    "basic" - {
      "is a TypeSafe Config" in {
        VerityLocalConfig.standard(tempDir, defaultPorts) shouldBe an[Config]
      }

      "should in memory persistence" in {
        VerityLocalConfig.standard(tempDir,defaultPorts).getString("akka.persistence.journal.plugin") should include ("leveldb")
      }

      "should use local snapshot" in {
        VerityLocalConfig.standard(tempDir,defaultPorts).getString("akka.persistence.snapshot-store.plugin") should include ("snapshot-store.local")
      }

      "should use default wallet type" in {
        VerityLocalConfig.standard(tempDir,defaultPorts).getString("verity.lib-indy.wallet.type") should include ("default")
      }
    }
  }

  "LocalVerity" - {

    "should startup" ignore {
      LocalVerity(VerityNodeParam(tempDir, "11111111111111111111111111111111", defaultPorts), ecp)
    }
    "should be able to start multiple verity applications" ignore {
      val v1_dir = tempDir.resolve("v1")
      assert(v1_dir.toFile.mkdir())
      val v1 = LocalVerity(v1_dir, "11111111111111111111111111111111", PortProfile(9002, 2552, 8552, 9095), ecp)
      val v2_dir = tempDir.resolve("v2")
      assert(v2_dir.toFile.mkdir())
      val v2 = LocalVerity(v2_dir, "11111111111111111111111111111111", PortProfile(9003, 2553, 8553, 9095), ecp)
    }
  }
}
