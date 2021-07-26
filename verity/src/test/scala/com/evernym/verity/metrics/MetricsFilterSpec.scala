package com.evernym.verity.metrics

import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.ConfigFactory


class MetricsFilterSpec
  extends BasicSpec {

  "MetricsFilter" - {
    "when tested for non matching string" - {
      "should return true" in {
        val mf = MetricsFilter(ConfigFactory.parseString(
          """
            verity.metrics.writer.exclude = [
              "metric1"
            ]
            """.stripMargin))
        mf.isExcluded("metric") shouldBe false
      }
    }

    "when tested for matching string" - {
      "should return true" in {
        val mf = MetricsFilter(ConfigFactory.parseString(
          """
            verity.metrics.writer.exclude = [
              "metric"
            ]
            """.stripMargin))
        mf.isExcluded("metric") shouldBe true
      }
    }

    "when tested for matching regex" - {
      "should return true" in {
        val mf = MetricsFilter(ConfigFactory.parseString(
          """
            verity.metrics.writer.exclude = [
              "metric.*"
            ]
            """.stripMargin))
        mf.isExcluded("metric1") shouldBe true
      }
    }
  }
}
