package com.evernym.verity.actor.metrics

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.Main.platform
import com.evernym.verity.ReqId
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.node_singleton.MsgProgressTrackerCache
import com.evernym.verity.actor.persistence.Done
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually


//NOTE: This is not a feature code, its a utility code to troubleshooting msg progress in a system
class LibindyMetricsTrackerSpec
  extends TestKitBase
//    with ProvidesMockPlatform
    with BasicSpec
    with ImplicitSender
    with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
  lazy val libindyMetricsTracker: ActorRef = platform.createLibindyMetricsTrackerActor()
  lazy val clientIpAddress: String = "127.0.0.1"
  lazy val reqId: ReqId = UUID.randomUUID().toString

  "LibindyMetricsTracker" - {

    "collected metrics from Libindy" - {
      "should be sent to Kamon" in {
        sendToIpAddressTracker(LibindyMetricsTick())
        // add a check for sending metrics to Kamon
      }
    }
  }
  def sendToIpAddressTracker(msg: Any): Unit = {
      sendToRegion(clientIpAddress, msg)
  }
  def sendToRegion(id: String, msg: Any): Unit = {
    libindyMetricsTracker ! ForIdentifier(id, msg)
  }
}
