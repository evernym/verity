package com.evernym.verity.metrics

import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.ConfigFactory


class MetricsFilterSpec
  extends BasicSpec {

  "MetricsFilter" - {
    "when tested for non excluded metrics" - {
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

    "when tested for excluded metrics" - {
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

    "when tested with excludes as regex (example 1)" - {
      "should return true" in {
        val mf = MetricsFilter(ConfigFactory.parseString(
          """
            verity.metrics.writer.exclude = [
              "metric.*"
            ]
            """.stripMargin))
        mf.isExcluded("metric1") shouldBe true
        mf.isExcluded("metric123") shouldBe true
        mf.isExcluded("metricabc") shouldBe true
      }
    }

    "when tested with excludes as regex (example 2)" - {
      "should return true" in {
        val mf = MetricsFilter(ConfigFactory.parseString(
          """
            verity.metrics.writer.exclude = [
              ".*metric.*",
              "another-metrics"
            ]
            """.stripMargin))
        mf.isExcluded("metric") shouldBe true
        mf.isExcluded("metric123") shouldBe true
        mf.isExcluded("metricabc") shouldBe true
        mf.isExcluded("123metric") shouldBe true
        mf.isExcluded("abcmetric") shouldBe true
        mf.isExcluded("123metric123") shouldBe true
        mf.isExcluded("abcmetricabc") shouldBe true
        mf.isExcluded("nonmatching") shouldBe false
      }
    }
  }
}
