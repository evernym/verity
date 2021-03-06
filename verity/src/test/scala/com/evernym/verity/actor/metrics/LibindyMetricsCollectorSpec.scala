package com.evernym.verity.actor.metrics

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.ReqId
import com.evernym.verity.actor.MetricsFilterCriteria
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually

import java.util.UUID
import scala.concurrent.duration.DurationInt

class LibindyMetricsCollectorSpec
  extends TestKitBase
    with ProvidesMockPlatform
    with BasicSpec
    with ImplicitSender
    with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
  lazy val libindyMetricsCollector: ActorRef = platform.libIndyMetricsCollector
  lazy val clientIpAddress: String = "127.0.0.1"
  lazy val reqId: ReqId = UUID.randomUUID().toString

  "LibindyMetricsCollector" - {
    "collected metrics from Libindy" - {
      "should be sent to Kamon" in {
        libindyMetricsCollector ! CollectLibindyMetrics()
        expectMsgType[CollectLibindySuccess]
        val criteria = MetricsFilterCriteria(filtered = false)
        awaitCond(
          MetricsReader.getNodeMetrics(criteria).metrics.exists(
            metricDetail => metricDetail.name.contains("libindy_wallet_count")
            //TODO should be added again once we switch to libindy-async
//            metricDetail => metricDetail.name.contains("libindy_command_duration_ms_count") &&
//              metricDetail.value.isValidInt &&
//              metricDetail.tags.get.contains("command") &&
//              metricDetail.tags.get.contains("stage")
          ),
          60.seconds
        )
      }
    }
  }
}
