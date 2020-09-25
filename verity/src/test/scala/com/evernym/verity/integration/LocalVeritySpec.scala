package com.evernym.verity.integration

import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.fixture.TempDir
import com.evernym.verity.http.base.AgentReqBuilder
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.integration.LocalVerityConfig.defaultPorts
import com.typesafe.config.Config


class LocalVeritySpec extends BasicSpec with AgentReqBuilder with CommonSpecUtil with TempDir {
  override def deleteFiles: Boolean = false

  "LocalConfig" - {
    "basic" - {
      "is a TypeSafe Config" in {
        LocalVerityConfig.standard(tempDir, defaultPorts) shouldBe an[Config]
      }

      "should in memory persistence" in {
        LocalVerityConfig.standard(tempDir,defaultPorts).getString("akka.persistence.journal.plugin") should include ("inmem")
      }

      "should use local snapshot" in {
        LocalVerityConfig.standard(tempDir,defaultPorts).getString("akka.persistence.snapshot-store.plugin") should include ("snapshot-store.local")
      }

      "should use default wallet type" in {
        LocalVerityConfig.standard(tempDir,defaultPorts).getString("verity.lib-indy.wallet.type") should include ("default")
      }
    }
  }

  "LocalVerity" - {

    "should startup" ignore {
      LocalVerity(tempDir, defaultPorts, "11111111111111111111111111111111")
    }
    "should be able to start multiple verity applications" ignore {
      val v1_dir = tempDir.resolve("v1")
      assert(v1_dir.toFile.mkdir())
      val v1 = LocalVerity(v1_dir, PortProfile(9002, 2552, 8552), "11111111111111111111111111111111")
      val v2_dir = tempDir.resolve("v2")
      assert(v2_dir.toFile.mkdir())
      val v2 = LocalVerity(v2_dir, PortProfile(9003, 2553, 8553), "11111111111111111111111111111111")
    }
  }
}
