package com.evernym.verity.config

import com.evernym.verity.actor.testkit.TestAppConfig
import com.typesafe.config.{ConfigException, ConfigFactory}
import com.evernym.verity.testkit.BasicSpec


class ConfigUtilSpec extends BasicSpec {
  "lastKeySegment" - {
    "should handle empty string" in {
      assertThrows[ConfigException.BadPath] {
        ConfigUtil.lastKeySegment("")
      }
    }
    "should handle null string" in {
      assertThrows[ConfigException.BadPath] {
        ConfigUtil.lastKeySegment(null)
      }
    }
    "should handle non-segmented key" in {
      ConfigUtil.lastKeySegment("test") shouldBe "test"
    }
    "should handle two segmented key" in {
      ConfigUtil.lastKeySegment("foo.bar") shouldBe "bar"
    }
    "should handle multi-segmented key" in {
      ConfigUtil.lastKeySegment("foo.fam.dev.bar") shouldBe "bar"
    }
    "should handle double period" in {
      assertThrows[ConfigException.BadPath] {
        ConfigUtil.lastKeySegment("foo..fam.dev.bar") shouldBe "bar"
      }

    }
    "should handle ignore period at end" in {
      assertThrows[ConfigException.BadPath] {
        ConfigUtil.lastKeySegment("foo.fam.dev.bar.") shouldBe "bar"
      }
      assertThrows[ConfigException.BadPath] {
        ConfigUtil.lastKeySegment("foo.fam.dev.bar..") shouldBe "bar"
      }
    }
  }

  "findAgentSpecificConfig" - {
    val validMap = ConfigFactory.parseString(
    """
       |msg-template {
       |  sms-offer-template-deeplink-url = "https://connectme.app.link?t=#{token}"
       |  agent-specific {
       |    8kLWtRSbRthozq4kTM6dge = {
       |      sms-offer-template-deeplink-url = "https://masterlink.app.link?t=#{token}"
       |    }
       |  }
       |}
       |""".stripMargin)

    "should find agent specific value" in {
      val testConfig = new TestAppConfig(Some(validMap), true)
      val specificConfig = ConfigUtil.findAgentSpecificConfig(
        "msg-template.sms-offer-template-deeplink-url",
        Some("8kLWtRSbRthozq4kTM6dge"),
        testConfig
      )
      specificConfig shouldBe "https://masterlink.app.link?t=#{token}"

      val specificConfig2 = ConfigUtil.findAgentSpecificConfig(
        "msg-template.sms-offer-template-deeplink-url",
        Some("NOT_FOUND"),
        testConfig
      )
      specificConfig2 shouldBe "https://connectme.app.link?t=#{token}"
    }
  }
}
