//package com.evernym.verity.actor.msg_tracer.resp_time_tracker
//
//import java.util.UUID
//
//import akka.actor.ActorSystem
//import akka.testkit.{ImplicitSender, TestKitBase}
//import com.evernym.verity.actor.persistence.{AlreadyDone, Done}
//import com.evernym.verity.actor.testkit.AkkaTestBasic
//import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
//import com.evernym.verity.metrics.MetricsReporterApi
//import com.evernym.verity.msg_tracer.MsgTraceProvider
//import org.scalatest.concurrent.Eventually
//import com.evernym.verity.testkit.BasicSpec

//
//
//class MsgRespTimeTrackerLocalSpec
//  extends TestKitBase
//    with ProvidesMockPlatform
//    with BasicSpec
//    with MsgTraceProvider
//    with Eventually {
//
//  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
//  lazy val reqId: String = UUID.randomUUID().toString
//
//  MetricsReporterApi
//
//  "Message Tracer Region" - {
//
//    "when asked to record request received" - {
//      "should be done successfully" in {
//        val resp = MsgRespTimeTracker.recordReqReceived(reqId, respMode=SendBackResp)
//        resp shouldBe Done
//      }
//    }
//
//    "when asked to record request received again" - {
//      "should be done successfully" in {
//        val resp = MsgRespTimeTracker.recordReqReceived(reqId)
//        resp shouldBe AlreadyDone
//      }
//    }
//
//    "when asked to record metrics" - {
//      "should be done successfully" in {
//        val resp = MsgRespTimeTracker.recordMetrics(reqId, "connReq")
//        resp shouldBe Done
//      }
//    }
//
//    //TODO: this test works individually, but when ran with all other test, it fails
////    "when asked MetricsReporterApi" - {
////      "should provide recorded metrics" in {
////        eventually (timeout(Span(20, Seconds)), interval(Span(2, Seconds))) {
////          val nodeMetrics = MetricsReporterApi.getNodeMetrics(MetricsFilterCriteria(includeReset=false, filtered=false))
////
////          //histogram metrics assertion
////          nodeMetrics.metrics.exists(
////            m => m.name == "histogram_processing_time_millis_bucket" &&
////              m.tags.getOrElse(Map.empty).exists(r => r._1 == "msg_type" && r._2 == "connReq")) shouldBe true
////
////          //span metrics assertion
////          nodeMetrics.metrics.exists(
////            m => m.name == "span_processing_time_seconds_bucket" &&
////              m.tags.getOrElse(Map.empty).exists(r => r._1 == "msg_type" && r._2 == "connReq")) shouldBe true
////        }
////      }
////    }
//
//  }
//}
