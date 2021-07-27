package com.evernym.verity.metrics

import com.evernym.verity.actor.testkit.HasBasicActorSystem
import com.evernym.verity.testkit.BasicSpec


class MetricsWriterSpec
  extends HasBasicActorSystem
    with BasicSpec {

  "MetricsWriter" - {

    "without filter configuration" - {
      "when asked to write a metric" - {
        "should be written successfully" in {
          val testMetricsBackend = new TestMetricsBackend
          val metricsWriter = new MetricsWriter(testMetricsBackend)
          metricsWriter.gaugeUpdate("metrics-written", 1)
          testMetricsBackend.filterGaugeMetrics("metrics-written").size shouldBe 1
        }
      }
    }

    "with filter configuration" - {

      "when asked to write a non filtered metric" - {
        "should be written successfully" in {
          val filters = Set("metrics-reader").map(_.r)
          val testMetricsBackend = new TestMetricsBackend
          val metricsWriter = new MetricsWriter(testMetricsBackend, filters)
          metricsWriter.gaugeUpdate("metrics-written", 1)
          testMetricsBackend.filterGaugeMetrics("metrics-written").size shouldBe 1
        }
      }

      "when asked to write a filtered metric" - {
        "should NOT be written" in {
          val filters = Set("metrics-written").map(_.r)
          val testMetricsBackend = new TestMetricsBackend
          val metricsWriter = new MetricsWriter(testMetricsBackend, filters)
          metricsWriter.gaugeUpdate("metrics-written", 1)
          testMetricsBackend.filterGaugeMetrics("metrics-written").size shouldBe 0
        }
      }

      "when asked to write a filtered metric (using reg ex)" - {
        "should NOT be written" in {
          val filters = Set("metrics-written.*").map(_.r)
          val testMetricsBackend = new TestMetricsBackend
          val metricsWriter = new MetricsWriter(testMetricsBackend, filters)

          metricsWriter.gaugeUpdate("metrics-written-1", 1)
          testMetricsBackend.filterGaugeMetrics("metrics-written-1").size shouldBe 0
        }
      }

      "when changed metrics at runtime" - {
        "should be successful" in {
          val testMetricsBackend = new TestMetricsBackend
          val metricsWriter = new MetricsWriter(testMetricsBackend)
          metricsWriter.gaugeUpdate("metrics-written-1", 1)
          testMetricsBackend.filterGaugeMetrics("metrics-written-1").size shouldBe 1

          val testNewMetricsBackend = new TestMetricsBackend
          metricsWriter.updateMetricsBackend(testNewMetricsBackend)
          testNewMetricsBackend.filterGaugeMetrics("metrics-written-1").size shouldBe 0
        }
      }

      "when changed filters at runtime" - {
        "should be successful" in {
          val testMetricsBackend = new TestMetricsBackend
          val metricsWriter = new MetricsWriter(testMetricsBackend)

          metricsWriter.gaugeUpdate("metrics-written-1", 1)
          testMetricsBackend.filterGaugeMetrics("metrics-written-1").size shouldBe 1

          metricsWriter.updateFilters(Set("metrics-updated.*").map(_.r))
          metricsWriter.gaugeUpdate("metrics-updated-1", 1)
          testMetricsBackend.filterGaugeMetrics("metrics-updated-1").size shouldBe 0
        }
      }
    }
  }
}
