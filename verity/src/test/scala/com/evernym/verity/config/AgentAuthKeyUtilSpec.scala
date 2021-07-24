package com.evernym.verity.config

import com.evernym.verity.config.ConfigConstants.AGENT_AUTHENTICATION_ENABLED
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.ConfigValueFactory

class AgentAuthKeyUtilSpec extends BasicSpec with ConfigUtilBaseSpec {

  lazy val appConfig: AppConfig = AppConfigWrapper

  "Agent auth key util" - {

    "when feature is disabled" - {
      "asked to load keys for any given id" - {
        "should respond with empty list" in {
          appConfig.setConfig(
            appConfig.config.withValue(
              AGENT_AUTHENTICATION_ENABLED,
              ConfigValueFactory.fromAnyRef(false)))
          val authedKeys = AgentAuthKeyUtil.keysForSelfRelDID(appConfig, "test-id")
          authedKeys shouldBe Set.empty
        }
      }
    }

    "when feature is enabled" - {
      "asked to load keys for non configured key" - {
        "should respond with empty list" in {
          appConfig.setConfig(
            appConfig.config.withValue(
              AGENT_AUTHENTICATION_ENABLED,
              ConfigValueFactory.fromAnyRef(true)))
          val authedKeys = AgentAuthKeyUtil.keysForSelfRelDID(appConfig, "test-id")
          authedKeys shouldBe Set.empty
        }
      }

      "asked to load keys for configured key" - {
        "should respond with set of the configured keys" in {
          appConfig.setConfig(
            appConfig.config.withValue(
              AGENT_AUTHENTICATION_ENABLED,
              ConfigValueFactory.fromAnyRef(true)))
          val authedKeys = AgentAuthKeyUtil.keysForSelfRelDID(appConfig, "domain-id-1")
          authedKeys shouldBe Set("key1", "key2")
        }
      }
    }

    "when authed keys updated in config file" - {
      "asked to load keys for configured key" - {
        "should respond with updated set of keys" in {
          withChangedConfigFileContent(
            Set(
              "verity/target/scala-2.12/test-classes/application.conf",
              //TODO: this test was failing on a dev machine (build pipeline is working fine though)
              // it can be fixed by uncommenting below line (but then, it fails on build pipeline)
              // "verity/target/scala-2.12/multi-jvm-classes/application.conf",
            ),
            """domain-id-1: ["key1", "key2"]""",
            """domain-id-1: ["key1", "key2", "key3"]""", {
            appConfig.reload()
            val authedKeys = AgentAuthKeyUtil.keysForSelfRelDID(appConfig, "domain-id-1")
            authedKeys shouldBe Set("key1", "key2", "key3")
          })
        }
      }
    }

  }
}
