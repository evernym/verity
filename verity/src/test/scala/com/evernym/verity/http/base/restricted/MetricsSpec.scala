package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.metrics.{MetricsReader, NodeMetricsData}
import com.evernym.verity.metrics.reporter.MetricDetail
import org.scalatest.time.{Seconds, Span}

trait MetricsSpec { this : EndpointHandlerBaseSpec =>

  MetricsReader  //this makes sure it starts/add prometheus reporter and adds it to Kamon

  def testMetrics(): Unit = {
    "when sent get metrics api call" - {
      "should response with metrics" taggedAs (UNSAFE_IgnoreLog) in {
        eventually(timeout(Span(5, Seconds))) {
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

    "when sent reset metrics api call" - {
      "respond successfully" taggedAs (UNSAFE_IgnoreLog) in {
        buildPutReq("/agency/internal/metrics/reset") ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }
  }

  def checkExpectedMetrics(metrics: List[MetricDetail]): Unit = {
    val expectedMetrics = Set(
      "jvm_memory_pool_committed_bytes",
      "span_processing_time_seconds_count", "span_processing_time_seconds_sum", "span_processing_time_seconds_bucket",
      "jvm_memory_pool_free_bytes_count",   "jvm_memory_pool_free_bytes_sum",   "jvm_memory_pool_free_bytes_bucket",
      "jvm_gc_seconds_count",               "jvm_gc_seconds_sum",               "jvm_gc_seconds_bucket",
      "jvm_memory_used_bytes_count",        "jvm_memory_used_bytes_sum",        "jvm_memory_used_bytes_bucket",
      "libindy_threadpool_threads_count"
    )
    expectedMetrics.foreach { emn =>
      val pmn = MetricsReader.convertToProviderName(emn)
      metrics.exists(_.name == pmn) shouldBe true
    }
  }

}
