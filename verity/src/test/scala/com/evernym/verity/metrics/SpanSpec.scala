package com.evernym.verity.metrics

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.testkit.{AddMetricsReporter, BasicSpec}
import kamon.Kamon
import kamon.metric.MeasurementUnit
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.concurrent.Future

class SpanSpec extends BasicSpec with AddMetricsReporter with Eventually {

  "runWithInternalSpan" - {
    "when tested with synchronous code" - {
      "should report correct time" in {
        runWithInternalSpan("opname-1", "component-1") {
          Thread.sleep(2000)
        }
        checkSpan("span_processing_time_seconds_sum", "opname-1", "component-1", 1.9)
      }
    }

    "when tested with asynchronous code" - {
      "should report correct time" in {
        val startTime = Instant.now()
        Future {
          Thread.sleep(4000)
        }.map { r =>
          val endTime = Instant.now()
          val seconds = ChronoUnit.SECONDS.between(startTime, endTime)
          Kamon
            .histogram("span_processing_time_seconds", MeasurementUnit.time.seconds)
            .withTag("operation", "opname-2")
            .withTag("component", "component-2")
            .record(seconds)
          r
        }
        checkSpan("span_processing_time_seconds_sum", "opname-2", "component-2", 3.9)
      }
    }
  }

  def checkSpan(metricName: String, opName: String, componentName:String, expectMinValue: Double): Unit = {
    eventually (timeout(Span(10, Seconds)), interval(Span(3, Seconds))) {
      val metrics =  MetricsReader.getNodeMetrics().metrics
      val filteredMetric = metrics
          .filter(_.name == metricName)
          .find { md =>
            val tags = md.tags.getOrElse(Map.empty)
            tags.exists(t => t._1 == "operation" && t._2 == opName) &&
              tags.exists(t => t._1 == "component" && t._2 == componentName)
          }
      filteredMetric.nonEmpty shouldBe true
      filteredMetric.get.value >= expectMinValue shouldBe true
    }
  }
}
