package com.evernym.verity.actor.metrics

import java.util.UUID

import akka.Done
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.ReqId
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually

class LibindyMetricsCollectorSpec
  extends TestKitBase
    with ProvidesMockPlatform
    with BasicSpec
    with ImplicitSender
    with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
  lazy val libindyMetricsTracker: ActorRef = platform.createLibindyMetricsCollectorActor()
  lazy val clientIpAddress: String = "127.0.0.1"
  lazy val reqId: ReqId = UUID.randomUUID().toString

  "LibindyMetricsTracker" - {

    "collected metrics from Libindy" - {
      "should be sent to Kamon" in {
        libindyMetricsTracker ! CollectLibindyMetrics()
        expectMsgType[Done]
        // add a check for sending metrics to Kamon
      }
    }
  }
}
