package com.evernym.verity.config

import com.evernym.verity.actor.agent.SponsorRel
import com.evernym.verity.actor.metrics.{ActiveRelationships, ActiveUsers, ActiveWindowRules, CalendarMonth, VariableDuration}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.typesafe.config.{ConfigException, ConfigFactory}
import com.evernym.verity.testkit.BasicSpec

import scala.concurrent.duration.Duration

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

    "findActivityWindow" - {
      val activityConfig = ConfigFactory.parseString(
        """
          |verity {
          |   metrics {
          |     activity-tracking {
          |       active-user {
          |          time-windows = ["3 d", "30 day", "20 min"]
          |          monthly-window = false
          |          enabled = true
          |       }
          |
          |       active-relationships {
          |         time-windows = []
          |         monthly-window = true
          |         enabled = true
          |       }
          |     }
          |   }
          | }
          |""".stripMargin)

      "should find activity window" in {
        val testConfig = new TestAppConfig(Some(activityConfig), true)
        val windows = ConfigUtil.findActivityWindow(testConfig)
        assert(windows.windows.size == 4)
        val expectedWindows = Set(
          ActiveWindowRules(VariableDuration("3 d"), ActiveUsers),
          ActiveWindowRules(VariableDuration("30 day"), ActiveUsers),
          ActiveWindowRules(VariableDuration("20 min"), ActiveUsers),
          ActiveWindowRules(CalendarMonth, ActiveRelationships),
        )
        assert(windows.windows == expectedWindows)
      }
    }

    "when dealing with sponsor rel configs" - {
      def activityConfig(sponsorId: Boolean=false, sponseeId: Boolean=false) = {
        ConfigFactory.parseString(
          s"""
            |verity {
            |   metrics {
            |     protocol {
            |       tags {
            |        uses-sponsor=$sponsorId
            |        uses-sponsee=$sponseeId
            |      }
            |     }
            |   }
            | }
            |""".stripMargin)
      }

      "should check sponsor flag" in {
        // doesn't use sponsor
        val noSponsor = new TestAppConfig(Some(activityConfig()), true)
        assert(!ConfigUtil.sponsorMetricTagEnabled(noSponsor))

        // uses sponsor
        val hasSponsor = new TestAppConfig(Some(activityConfig(sponsorId=true)), true)
        assert(ConfigUtil.sponsorMetricTagEnabled(hasSponsor))
      }

      "should check sponsee flag" in {
        // doesn't use sponsor
        val noSponsee = new TestAppConfig(Some(activityConfig()), true)
        assert(!ConfigUtil.sponseeMetricTagEnabled(noSponsee))

        // uses sponsor
        val hasSponsee = new TestAppConfig(Some(activityConfig(sponseeId=true)), true)
        assert(ConfigUtil.sponseeMetricTagEnabled(hasSponsee))
      }

      "should get sponsorRelTag" in {
        val sponsorRel = SponsorRel("my-name", "her-name")
        // doesn't use sponsor or sponsee
        var testConfig = new TestAppConfig(Some(activityConfig()), true)
        var tags = ConfigUtil.getSponsorRelTag(testConfig, sponsorRel)
        assert(tags.isEmpty)

        // uses sponsor but not sponsee
        testConfig = new TestAppConfig(Some(activityConfig(sponsorId=true)), true)
        tags = ConfigUtil.getSponsorRelTag(testConfig, sponsorRel)
        assert(tags == Map("sponsorId" -> sponsorRel.sponsorId))

        // uses sponsee but not sponsor
        testConfig = new TestAppConfig(Some(activityConfig(sponseeId=true)), true)
        tags = ConfigUtil.getSponsorRelTag(testConfig, sponsorRel)
        assert(tags == Map("sponseeId" -> sponsorRel.sponseeId))

        // uses both
        testConfig = new TestAppConfig(Some(activityConfig(sponsorId=true, sponseeId=true)), true)
        tags = ConfigUtil.getSponsorRelTag(testConfig, sponsorRel)
        assert(tags == Map("sponsorId" -> sponsorRel.sponsorId, "sponseeId" -> sponsorRel.sponseeId))
      }
    }
  }

  "when getting dataRetentionPolicy" - {
    val domainProtocolPolicy = "7 d"
    val domainFallback = "1d"
    val defaultProtocolPolicy = "3 day"
    val defaultFallback = "365 days"
    val domainId = "domainId123"
    val dataRetentionConfig = ConfigFactory.parseString(
      s"""
        |verity {
        |   retention-policy {
        |     domainId123 {
        |       basicmessage = $domainProtocolPolicy
        |       max-proto = "731 d"
        |       years-proto = "1 years"
        |       undefined-fallback = $domainFallback
        |     }
        |
        |     default {
        |       undefined-fallback = $defaultFallback
        |       relationship = $defaultProtocolPolicy
        |     }
        |   }
        | }
        |""".stripMargin)

    val missingFallbackConfig = ConfigFactory.parseString(
      """
        |verity {
        |   retention-policy {
        |     missing-fallback { }
        |     default { }
        |   }
        | }
        |""".stripMargin)

    val testConfig = new TestAppConfig(Some(dataRetentionConfig), clearValidators=true)
    val testMissingFallbackConfig = new TestAppConfig(Some(missingFallbackConfig), clearValidators=true)

    "should fail with greater than max value" in {
      intercept[ConfigException.BadValue] {
        ConfigUtil.getDataRetentionPolicy(testConfig, domainId, "max-proto")
      }
    }

    "should fail with anything different than days" in {
      intercept[ConfigException.BadValue] {
        ConfigUtil.getDataRetentionPolicy(testConfig, domainId, "years-proto")
      }
    }

    "should find defined policy for domain id" in {
      assert(ConfigUtil.getDataRetentionPolicy(testConfig, domainId, "basicmessage") == ConfigUtil.dataRetentionTag(domainProtocolPolicy))
    }

    "should select domain's 'undefined-default' when protocol not defined" in {
      assert(ConfigUtil.getDataRetentionPolicy(testConfig, domainId, "relationship") == ConfigUtil.dataRetentionTag(domainFallback))// domain's undefined-fallback
    }

    "should throw exception when domain is registered with no fallback" in {
      intercept[ConfigException.Missing] {
        ConfigUtil.getDataRetentionPolicy(testMissingFallbackConfig, "missing-fallback", "relationship")
      }
    }

    "should select default's defined protocol policy when domain isn't registered" in {
      assert(ConfigUtil.getDataRetentionPolicy(testConfig, "not registered", "relationship") == ConfigUtil.dataRetentionTag(defaultProtocolPolicy))
    }

    "should select default's 'undefined-default' when protocol not defined for domain or default" in {
      assert(ConfigUtil.getDataRetentionPolicy(testConfig, "not registered", "questionanswer") == ConfigUtil.dataRetentionTag(defaultFallback))
    }

    "should select default's 'undefined-default' when domain id not provided" in {
      assert(ConfigUtil.getDataRetentionPolicy(testConfig, "", "questionanswer") == ConfigUtil.dataRetentionTag(defaultFallback))
    }

    "should select domainId's 'undefined-default' when protoref not provided" in {
      assert(ConfigUtil.getDataRetentionPolicy(testConfig, domainId, "") == ConfigUtil.dataRetentionTag(domainFallback))
    }

    "should throw exception with no fallback" in {
      intercept[ConfigException.Missing] {
        ConfigUtil.getDataRetentionPolicy(testMissingFallbackConfig, "not registered", "123")
      }
    }
  }
}
