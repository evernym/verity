package com.evernym.verity

import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import scala.jdk.CollectionConverters._

import scala.util.Try

class ConfigSpec
  extends BasicSpec {

  "Config" - {
    "when extracted a value as string" - {
      "should be successful" in {
        val config = ConfigFactory.parseString(
          """
           {
             push-msg-overrides: {
               "general-new-msg-body-template" = "Sponsor: Your credit union has sent you #{msgType}"
               "questionanswer_1.0_question-new-msg-body-template" = "Sponsor: Your credit union is asking you #{msgType}"
             }
           }
          """.stripMargin)

        val pushMsgOverrides = Try {
          config.getConfig("push-msg-overrides").root().render(ConfigRenderOptions.concise())
        }.getOrElse("{}")

        val pushConfig =
          ConfigFactory
            .parseString(pushMsgOverrides).root()
            .entrySet()
            .asScala
            .map (entry => entry.getKey -> entry.getValue.unwrapped().toString)
            .toMap

        val key1 = "general-new-msg-body-template"
        pushConfig.get(key1) shouldBe Option("Sponsor: Your credit union has sent you #{msgType}")

        val key2 = "questionanswer_1.0_question-new-msg-body-template"
        pushConfig.get(key2) shouldBe Option("Sponsor: Your credit union is asking you #{msgType}")
      }
    }
  }
}