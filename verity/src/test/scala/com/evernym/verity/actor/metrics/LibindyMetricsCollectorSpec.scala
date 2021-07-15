package com.evernym.verity.actor.metrics

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.util2.ReqId
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
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
        awaitCond(
          testMetricsWriter.filterGaugeMetrics("libindy_command_duration_ms_count").exists(entry =>
            entry._2.isValidInt &&
              entry._1.tags.contains("command") &&
              entry._1.tags.contains("stage")
          ),
          60.seconds
        )
      }
    }
  }
}
