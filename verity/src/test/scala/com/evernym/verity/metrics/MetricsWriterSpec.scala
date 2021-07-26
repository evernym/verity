package com.evernym.verity.metrics

import com.evernym.verity.actor.testkit.HasBasicActorSystem
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.config.{Config, ConfigFactory}

class MetricsWriterSpec
  extends BasicSpec {

  "MetricsWriter" - {

    "without filter configuration" - {
      "when asked to write a metric" - {
        "should be written successfully" in {
          val metricsTester = new MetricsTester(None)
          metricsTester.metricsWriter.gaugeUpdate("metrics-written", 1)
          metricsTester.testMetricsBackend.filterGaugeMetrics("metrics-written").size shouldBe 1
        }
      }
    }

    "with filter configuration" - {
      "when asked to write a non filtered metric" - {
        "should be written successfully" in {
          val config = ConfigFactory.parseString(
            """
              verity.metrics.writer.exclude = [
                "metrics-reader"
              ]

              """.stripMargin
          )
          val metricsTester = new MetricsTester(Option(config))
          metricsTester.metricsWriter.gaugeUpdate("metrics-written", 1)
          metricsTester.testMetricsBackend.filterGaugeMetrics("metrics-written").size shouldBe 1
        }
      }

      "when asked to write a filtered metric" - {
        "should NOT be written" in {
          val config = ConfigFactory.parseString(
            """
              verity.metrics.writer.exclude = [
                "metrics-written"
              ]

              """.stripMargin
          )
          val metricsTester = new MetricsTester(Option(config))

          metricsTester.metricsWriter.gaugeUpdate("metrics-written", 1)
          metricsTester.testMetricsBackend.filterGaugeMetrics("metrics-written").size shouldBe 0
        }
      }

      "when asked to write a filtered metric (by reg ex)" - {
        "should NOT be written" in {
          val config = ConfigFactory.parseString(
            """
              verity.metrics.writer.exclude = [
                "metrics-written.*"
              ]

              """.stripMargin
          )
          val metricsTester = new MetricsTester(Option(config))

          metricsTester.metricsWriter.gaugeUpdate("metrics-written-1", 1)
          metricsTester.testMetricsBackend.filterGaugeMetrics("metrics-written-1").size shouldBe 0
        }
      }
    }
  }
}

class MetricsTester(override val overrideConfig: Option[Config])
  extends HasBasicActorSystem {
  val testMetricsBackend: TestMetricsBackend = new TestMetricsBackend
  val metricsWriter: MetricsWriter = MetricsWriterExtension(system).get()
  metricsWriter.updateMetricsBackend(testMetricsBackend)
}
