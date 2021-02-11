package com.evernym.verity.actor.metrics

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.actor.MetricsFilterCriteria
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration.DurationInt

class UserAgentMetricsCollectorSpec extends TestKitBase
  with ProvidesMockPlatform
  with BasicSpec
  with ImplicitSender
  with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
  lazy val metricsCollector: ActorRef = platform.userAgentMetricsCollector

  val key1 = "KEY1"
  val key2 = "KEY2"

  val avgName = "relationshipagents_avg"
  val maxName = "relationshipagents_max"

  "UserAgentMetricsCollectorSpec" - {
    "should add new metrics" in {
      metricsCollector ! UpdateUserAgentMetric(key1, 10)

      metricsCollector ! WriteUserAgentMetrics()

      awaitForMetrics(max = 10, avg = 10)
    }

    "should update exsiting metrics" in {
      metricsCollector ! UpdateUserAgentMetric(key1, 20)

      metricsCollector ! WriteUserAgentMetrics()

      awaitForMetrics(max = 20, avg = 20)
    }

    "should remove metrics" in {
      metricsCollector ! RemoveUserAgentMetric(key1)

      metricsCollector ! WriteUserAgentMetrics()

      awaitForMetrics(max = 0, avg = 0)
    }

    "should calculate average" in {
      metricsCollector ! UpdateUserAgentMetric(key1, 10)
      metricsCollector ! UpdateUserAgentMetric(key2, 50)

      metricsCollector ! WriteUserAgentMetrics()

      awaitForMetrics(max = 50, avg = 30)
    }
  }

  private def awaitForMetrics(max: Double, avg: Double): Unit = {
    val criteria = MetricsFilterCriteria(filtered = false)
    awaitCond(
      MetricsReader.getNodeMetrics(criteria).metrics.exists(metricDetail => {
        metricDetail.name.contains(maxName) &&
          metricDetail.value.equals(max)
      }) &&
        MetricsReader.getNodeMetrics(criteria).metrics.exists(metricDetail => {
          metricDetail.name.contains(avgName) &&
            metricDetail.value.equals(avg)
        }),
      60.seconds
    )
  }
}
