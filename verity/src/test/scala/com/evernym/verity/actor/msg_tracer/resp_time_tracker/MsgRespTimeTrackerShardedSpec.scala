package com.evernym.verity.actor.msg_tracer.resp_time_tracker

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.actor.persistence.{AlreadyDone, Done}
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.msg_tracer.MsgTraceProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import com.evernym.verity.testkit.BasicSpec



class MsgRespTimeTrackerShardedSpec
  extends TestKitBase
    with ProvidesMockPlatform
    with BasicSpec
    with ImplicitSender
    with MsgTraceProvider
    with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()

  lazy val reqId: String = UUID.randomUUID().toString

  override def msgSender: Option[ActorRef] = Some(self)

  MetricsReader    //this is to start kamon prometheus reporter
  platform

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

    //TODO: this test works individually, but when ran with all other test, it fails
//    "when asked MetricsReporterApi" - {
//      "should provide recorded metrics" in {
//        eventually (timeout(Span(20, Seconds)), interval(Span(2, Seconds))) {
//          val nodeMetrics = MetricsReporterApi.getNodeMetrics(MetricsFilterCriteria(includeReset=false, filtered=false))
//
//          //histogram metrics assertion
//          nodeMetrics.metrics.exists(
//            m => m.name == "histogram_processing_time_millis_bucket" &&
//              m.tags.getOrElse(Map.empty).exists(r => r._1 == "msg_type" && r._2 == "connReq")) shouldBe true
//
//          //span metrics assertion
//          nodeMetrics.metrics.exists(
//            m => m.name == "span_processing_time_seconds_bucket" &&
//              m.tags.getOrElse(Map.empty).exists(r => r._1 == "msg_type" && r._2 == "connReq")) shouldBe true
//        }
//      }
//    }

  }
}
