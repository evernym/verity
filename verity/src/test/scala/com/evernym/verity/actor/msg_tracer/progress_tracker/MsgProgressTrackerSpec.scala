package com.evernym.verity.actor.msg_tracer.progress_tracker

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.ReqId
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.node_singleton.MsgProgressTrackerCache
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.agentmsg.DefaultMsgCodec
import org.scalatest.concurrent.Eventually
import com.evernym.verity.testkit.BasicSpec



//NOTE: This is not a feature code, its a utility code to troubleshooting msg progress in a system
class MsgProgressTrackerSpec
  extends TestKitBase
    with ProvidesMockPlatform
    with BasicSpec
    with ImplicitSender
    with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
  lazy val msgProgressTrackerRegion: ActorRef = platform.msgProgressTrackerRegion
  lazy val clientIpAddress: String = "127.0.0.1"
  lazy val reqId: ReqId = UUID.randomUUID().toString

  "Message Tracer Region" - {

    "when sent a StartTracking cmd" - {
      "should respond with TrackingConfigured" in {
        sendToIpAddressTracker(ConfigureTracking())
        expectMsgType[TrackingConfigured]
      }
    }

    "when sent few RecordEvent command" - {
      "should respond with Done" in {
        (1 to 10).foreach { i =>
          val domainId = i%3
          MsgProgressTrackerCache.startProgressTracking(domainId.toString)
          val connectionId = domainId%3
          val event = EventReceivedByAgent(getClass.getSimpleName,
            trackingParam = TrackingParam(Option(domainId.toString), Option(connectionId.toString)),
            inMsgParam =  TrackMsgParam(msgName = Option("msg-name")))

          sendToIpAddressTracker(RecordEvent(UUID.randomUUID().toString, EventParam(event)))
          expectMsg(Done)
        }
      }
    }

    "when sent a GetEvents without asking for details" - {
      "should respond with requests without events" in {
        sendToIpAddressTracker(GetRecordedRequests())
        val re = expectMsgType[RecordedRequests]
        re.requests.size shouldBe 10
        re.requests.forall(_.events.isEmpty) shouldBe true
      }
    }

    "when sent a GetEvents command with asking for details" - {
      "should respond with recorded events" in {
        (1 to 10).foreach { i =>
          val domainId = i%3
          sendToRegion(domainId.toString, GetRecordedRequests(withEvents = Option(YES)))
          val re = expectMsgType[RecordedRequests]
          re.requests.size < 10 shouldBe true
          re.requests.forall(_.events.nonEmpty) shouldBe true
        }
      }
    }

    "when tried to generate html from recorded events" - {
      "should be able to generate it successfully" in {
        sendToIpAddressTracker(GetRecordedRequests(withEvents = Option(YES)))
        val re = expectMsgType[RecordedRequests]
        re.requests.size shouldBe 10
        val serialized = DefaultMsgCodec.toJson(re)
        DefaultMsgCodec.fromJson[RecordedRequests](serialized)
      }
    }
  }

  def sendToIpAddressTracker(msg: Any): Unit = {
    sendToRegion(clientIpAddress, msg)
  }

  def sendToRegion(id: String, msg: Any): Unit = {
    msgProgressTrackerRegion ! ForIdentifier(id, msg)
  }
}
