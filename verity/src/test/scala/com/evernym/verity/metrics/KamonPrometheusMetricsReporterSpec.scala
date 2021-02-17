package com.evernym.verity.metrics

import com.evernym.verity.metrics.reporter.{KamonPrometheusMetricsReporter, MetricDetail}
import com.evernym.verity.testkit.BasicSpec


class KamonPrometheusMetricsReporterSpec extends BasicSpec {

  val inputMetrics: String = """# TYPE akka_system_processed_messages_total counter
                       |akka_system_processed_messages_total{system="consumer-agency",tracked="false"} 289978.0
                       |akka_system_processed_messages_total{system="consumer-agency",tracked="tr"} 0.0
                       |span_processing_time_seconds_bucket{operation="/agency/agency/agency/agency/agency/agency/agency/agency/agency/internal/msg-progress-tracker/{}",span_kind="server",http_status_code="200",error="false",le="0.005",component="akka.http.server",http_method="POST"} 0.0
                       |span_processing_time_seconds_bucket{operation="msg-latency",msg_type="CREATE_AGENT:AGENT_CREATED",parentOperation="tell(MsgTracer, CaptureMetrics)",error="false",le="0.005",next_hop="my edge agent (synchronous)"} 0.0""".stripMargin

  "KamonPrometheusMetricsProvider" - {

    "when tested the metrics name extractor" - {
      "should be able to extract name of metrics" in {
        val inputStr =
          """akka_actor_errors_total{path="consumer-agency/user/singleton-parent-proxy",
            |system="consumer-agency",dispatcher="akka.actor.default-dispatcher",
            |class="akka.cluster.singleton.ClusterSingletonProxy"} 0.0""".stripMargin
        KamonPrometheusMetricsReporter.buildMetric(inputStr).map(_.name) shouldBe Some("akka_actor_errors_total")

        val inputStr1 = """tracer_spans_created_total 215870.0"""
        KamonPrometheusMetricsReporter.buildMetric(inputStr1).map(_.name) shouldBe Some("tracer_spans_created_total")
      }
    }

    "when tested the metrics value extractor" - {
      "should be able to extract value of metrics" in {
        val inputStr =
          """akka_actor_errors_total{path="consumer-agency/user/singleton-parent-proxy",
            |system="consumer-agency",dispatcher="akka.actor.default-dispatcher",
            |class="akka.cluster.singleton.ClusterSingletonProxy"} 0.0"""
        KamonPrometheusMetricsReporter.buildMetric(inputStr).map(_.value) shouldBe Option(0.0)

        val inputStr1 = """tracer_spans_created_total 215870.0"""
        KamonPrometheusMetricsReporter.buildMetric(inputStr1).map(_.value) shouldBe Option(215870.0)
      }
    }

    "when tested the metrics target extractor" - {
      "should be able to extract target of metrics" in {
        val inputStr =
          """akka_actor_errors_total{path="consumer-agency/user/singleton-parent-proxy",system="consumer-agency",
            |dispatcher="akka.actor.default-dispatcher",class="akka.cluster.singleton.ClusterSingletonProxy"} 0.0"""
        val expectedVal = "consumer-agency/user/singleton-parent-proxy"
        KamonPrometheusMetricsReporter.buildMetric(inputStr).map(_.target) shouldBe Some(expectedVal)

        val inputStr1 = """tracer_spans_created_total 215870.0"""
        KamonPrometheusMetricsReporter.buildMetric(inputStr1).map(_.target) shouldBe Some("unknown")

        val inputStr2 =
          """executor_pool{actor_system="consumer-agency", type="fjp",
            |name="akka.remote.default-remote-dispatcher", setting="parallelism"} 4.0""".stripMargin

        KamonPrometheusMetricsReporter.buildMetric(inputStr2).map(_.target) shouldBe
          Some("akka.remote.default-remote-dispatcher-fjp-parallelism")
      }
    }

    "when asked to buildTags" - {
      "should be able to extract tags" in {
        val inputStr =
          """akka_actor_errors_total{path="consumer-agency/user/singleton-parent-proxy",system="consumer-agency",
            |dispatcher="akka.actor.default-dispatcher",class="akka.cluster.singleton.ClusterSingletonProxy"} 0.0"""
        val expectedVal = Map(
          "path" -> "consumer-agency/user/singleton-parent-proxy",
          "system" -> "consumer-agency",
          "dispatcher" -> "akka.actor.default-dispatcher",
          "class" -> "akka.cluster.singleton.ClusterSingletonProxy"
        )
        KamonPrometheusMetricsReporter.buildMetric(inputStr).flatMap(_.tags) shouldBe Option(expectedVal)

        val inputStr1 = """tracer_spans_created_total 215870.0"""
        KamonPrometheusMetricsReporter.buildMetric(inputStr1).flatMap(_.tags) shouldBe Option(Map.empty)
      }
    }

    "when asked to build metrics from provided input" - {
      "should be able to build metrics" in {
        val expectedMetrics = List(
          MetricDetail("akka_system_processed_messages_total", "actor_system", 289978.0,
            Some(Map("system" -> "consumer-agency", "tracked" -> "false"))),
          MetricDetail("akka_system_processed_messages_total", "actor_system", 0.0,
            Some(Map("system" -> "consumer-agency", "tracked" -> "tr")))
        )
        val actualMetrics = KamonPrometheusMetricsReporter.buildMetrics(inputMetrics)
        expectedMetrics.forall(actualMetrics.contains) shouldBe true
      }
    }

    "when asked to build metrics" - {
      "should be able to build metrics" in {
        val expectedMetrics = List(
          MetricDetail("akka_system_processed_messages_total", "actor_system", 289978.0,
            Some(Map("system" -> "consumer-agency", "tracked" -> "false"))),
          MetricDetail("akka_system_processed_messages_total", "actor_system", 0.0,
            Some(Map("system" -> "consumer-agency", "tracked" -> "tr")))
        )
        val actualMetrics = KamonPrometheusMetricsReporter.buildMetrics(inputMetrics)
        expectedMetrics.forall(actualMetrics.contains) shouldBe true
      }
    }
  }

}
