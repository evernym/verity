package com.evernym.verity.actor.msg_tracer.resp_time_tracker

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.actor.base.{AlreadyDone, Done}
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.msg_tracer.resp_time_tracker.MsgRespTimeTracker
import org.scalatest.concurrent.Eventually
import com.evernym.verity.testkit.{BasicSpec, PlatformInitialized}


class MsgRespTimeTrackerShardedSpec
  extends TestKitBase
    with ProvidesMockPlatform
    with PlatformInitialized
    with BasicSpec
    with ImplicitSender
    with MsgRespTimeTracker
    with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()

  lazy val reqId: String = UUID.randomUUID().toString

  override def msgSender: Option[ActorRef] = Some(self)

  "Message Tracer Region" - {

    "when asked to record request received" - {
      "should be done successfully" in {
        MsgRespTimeTracker.recordReqReceived(reqId, respMode = SendBackResp)
        expectMsg(Done)
      }
    }

    "when asked to record request received again" - {
      "should be done successfully" in {
        MsgRespTimeTracker.recordReqReceived(reqId)
        expectMsg(AlreadyDone)
      }
    }

    "when asked to record metrics" - {
      "should be done successfully" in {
        MsgRespTimeTracker.recordMetrics(reqId, "connReq", "test")
        expectMsg(Done)
      }
    }
  }
}
