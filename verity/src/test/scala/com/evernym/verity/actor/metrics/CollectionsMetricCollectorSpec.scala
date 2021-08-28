package com.evernym.verity.actor.metrics

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.observability.metrics.CustomMetrics
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
    awaitCond(
      testMetricsBackend.filterGaugeMetrics(CustomMetrics.AS_COLLECTIONS_MAX).exists(entry => {
          entry._1.tags.values.exists(_.equals(tag)) && entry._2.equals(max)
      }) &&
        testMetricsBackend.filterGaugeMetrics(CustomMetrics.AS_COLLECTIONS_SUM).exists(entry => {
          entry._1.tags.values.exists(_.equals(tag)) && entry._2.equals(sum)
        }) &&
        testMetricsBackend.filterGaugeMetrics(CustomMetrics.AS_COLLECTIONS_COUNT).exists(entry => {
          entry._1.tags.values.exists(_.equals(tag)) && entry._2.equals(cnt)
        }),
      60.seconds
    )
  }
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}
