package com.evernym.integrationtests.e2e.flow

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.metrics.reporter.MetricDetail
import com.evernym.verity.msg_tracer.MsgTraceProvider
import com.evernym.integrationtests.e2e.scenario.ApplicationAdminExt
import com.evernym.integrationtests.e2e.util.ReportDumpUtil
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}
import com.evernym.verity.testkit.BasicSpec



trait MetricsFlow {
  this: BasicSpec with Eventually =>

  def testMetrics(aae: ApplicationAdminExt): Unit = {
    s"when sent get metrics api call (${aae.name})" - {
      "should be able to fetch metrics" in {
        eventually(timeout(Span(5, Seconds)), interval(Span(1, Seconds))) {
          val allNodeMetrics = aae.getAllNodeMetrics()
          allNodeMetrics.data.headOption.nonEmpty shouldBe true
          val currentNodeMetrics = allNodeMetrics.data.flatMap(_.metrics)
          dumpMetrics(currentNodeMetrics, aae)
          testExpectedMetrics(currentNodeMetrics)
          testRespTimeSpanMetrics(currentNodeMetrics, aae)
        }
      }
    }
  }

  def testExpectedMetrics(metrics: List[MetricDetail]): Unit = {
    val expectedMetricsNames = Set(
      "akka_system_active_actors_sum",          "akka_system_active_actors_count",
      "akka_group_processing_time_seconds_sum", "akka_group_processing_time_seconds_count",

      "akka_group_time_in_mailbox_seconds_sum", "akka_group_time_in_mailbox_seconds_count", "akka_group_time_in_mailbox_seconds_bucket",
      "span_processing_time_seconds_sum",       "span_processing_time_seconds_count", "span_processing_time_seconds_bucket",

      "jvm_memory", "jvm_gc",
      "as_"
    )
    expectedMetricsNames.foreach { mn =>
      metrics.exists(_.name.contains(mn)) shouldBe true
    }
  }

  def testRespTimeSpanMetrics(metrics: List[MetricDetail], aae: ApplicationAdminExt): Unit = {
    val spanMetrics = filterRequiredMetrics(metrics, Set("span"))
//    checkExpectedSpanMetrics(spanMetrics)
    dumpMetrics(metrics, aae)
  }

  val expectedMetricsForMsgType = Set("credOffer", "credReq", "cred", "proofReq", "proof")

  //Note: add as many asserts/checks we want in this function for anything related to 'span' metrics
  private def checkExpectedSpanMetrics(metrics: List[MetricDetail]): Unit = {

    import MsgTraceProvider._
    val nextHops = Set(NEXT_HOP_THEIR_ROUTING_SERVICE, NEXT_HOP_MY_EDGE_AGENT, NEXT_HOP_MY_EDGE_AGENT_SYNC)
    expectedMetricsForMsgType.foreach { msgType =>
      val m = metrics.find(_.tags.getOrElse(Map.empty).exists(t => t._1 =="msg_type" && t._2.contains(msgType)))
      m.isDefined shouldBe true
      m.flatMap(_.tags).getOrElse(Map.empty)
        .exists(t => t._1 == "next_hop" && nextHops.contains(t._2)) shouldBe true
    }
  }

  /**
   *
   * @param metrics
   * @param names list of names which is compared against the metrics name
   *              to filter our required metrics
   * @return
   */
  def filterRequiredMetrics(metrics: List[MetricDetail], names: Set[String]=Set.empty): List[MetricDetail] = {
    metrics.filter(md => names.exists(md.name.contains))
  }

  def dumpMetrics(metrics: List[MetricDetail], aae: ApplicationAdminExt): Unit = {
    val jsonStr = DefaultMsgCodec.toJson(metrics)
    ReportDumpUtil.dumpData("Metrics", jsonStr, "metrics.json", aae)
  }
}
