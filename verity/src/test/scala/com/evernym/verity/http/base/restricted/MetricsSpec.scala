package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.metrics.reporter.MetricDetail
import com.evernym.verity.metrics.{MetricsReader, NodeMetricsData}
import com.evernym.verity.testkit.AddMetricsReporter
import org.scalatest.time.{Seconds, Span}


trait MetricsSpec extends AddMetricsReporter { this : EdgeEndpointBaseSpec =>

  def testMetrics(): Unit = {
    "when sent get metrics api call" - {
      "should response with metrics" taggedAs (UNSAFE_IgnoreLog) in {
        eventually(timeout(Span(10, Seconds)), interval(Span(3, Seconds))) {
          buildGetReq("/agency/internal/metrics") ~> epRoutes ~> check {
            status shouldBe OK
            val nmd = responseTo[NodeMetricsData]
            nmd.metrics.nonEmpty shouldBe true
            nmd.metadata.get.nodeName.nonEmpty shouldBe true
            nmd.metadata.get.timestamp.nonEmpty shouldBe true
            nmd.metadata.get.lastResetTimestamp.nonEmpty shouldBe true
            checkExpectedMetrics(nmd.metrics)
          }
        }
      }
    }
  }

  def checkExpectedMetrics(metrics: List[MetricDetail]): Unit = {
    val expectedMetrics = Set(
      "jvm_memory_pool_committed_bytes",
      "jvm_memory_pool_free_bytes_count",
      "jvm_memory_pool_free_bytes_sum",
      "jvm_memory_pool_free_bytes_bucket",
      "jvm_gc_seconds_count",
      "jvm_gc_seconds_sum",
      "jvm_gc_seconds_bucket",
      "jvm_memory_used_bytes_count",
      "jvm_memory_used_bytes_sum",
      "jvm_memory_used_bytes_bucket",
      "span_processing_time_seconds_count",
      "span_processing_time_seconds_sum",
      "span_processing_time_seconds_bucket",
//      "libindy_command_duration_ms_count", //TODO should be added again once we switch to libindy-async
//      "libindy_command_duration_ms_sum",
//      "libindy_command_duration_ms_bucket",
      "libindy_wallet_count"
    ).map(MetricsReader.convertToProviderName)
    val nameSet = metrics.map(_.name).toSet
    nameSet should contain allElementsOf (expectedMetrics)
    nameSet should not contain("libindy_command_duration_ms_count") // If this fails, add above entries back in
  }

}
