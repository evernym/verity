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

class CollectionsMetricCollectorSpec extends TestKitBase
  with ProvidesMockPlatform
  with BasicSpec
  with ImplicitSender
  with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
  lazy val metricsCollector: ActorRef = platform.collectionsMetricsCollector

  val key1 = "KEY1"
  val key2 = "KEY2"

  val metricMax = "collections_max"
  val metricCount = "collections_count"
  val metricSum = "collections_sum"

  val tag = "some-collection"

  "CollectionsMetricCollectorSpec" - {
    "should add new metrics" in {
      metricsCollector ! UpdateCollectionMetric(tag, key1, 10)

      awaitForMetrics(max = 10, sum = 10, cnt = 1)
    }

    "should update exsiting metrics" in {
      metricsCollector ! UpdateCollectionMetric(tag, key1, 20)

      awaitForMetrics(max = 20, sum = 20, cnt = 1)
    }

    "should remove metrics" in {
      metricsCollector ! RemoveCollectionMetric(tag, key1)

      awaitForMetrics(max = 0, sum = 0, cnt = 0)
    }

    "should calculate sum and max on update" in {
      metricsCollector ! UpdateCollectionMetric(tag, key1, 10)
      metricsCollector ! UpdateCollectionMetric(tag, key2, 50)

      awaitForMetrics(max = 50, sum = 60, cnt = 2)
    }

    "should calculate sum and max on remove" in {
      metricsCollector ! RemoveCollectionMetric(tag, key2)

      awaitForMetrics(max = 10, sum = 10, cnt = 1)
    }
  }

  private def awaitForMetrics(max: Double, sum: Double, cnt: Double): Unit = {
    val criteria = MetricsFilterCriteria(filtered = false)
    awaitCond(
      MetricsReader.getNodeMetrics(criteria).metrics.exists(metricDetail => {
        metricDetail.name.contains(metricMax) &&
          metricDetail.value.equals(max) &&
          metricDetail.tags.get.values.exists(_.equals(tag))
      }) &&
        MetricsReader.getNodeMetrics(criteria).metrics.exists(metricDetail => {
          metricDetail.name.contains(metricSum) &&
            metricDetail.value.equals(sum) &&
            metricDetail.tags.get.values.exists(_.equals(tag))
        }) &&
        MetricsReader.getNodeMetrics(criteria).metrics.exists(metricDetail => {
          metricDetail.name.contains(metricCount) &&
            metricDetail.value.equals(cnt) &&
            metricDetail.tags.get.values.exists(_.equals(tag))
        }),
      60.seconds
    )
  }
}
