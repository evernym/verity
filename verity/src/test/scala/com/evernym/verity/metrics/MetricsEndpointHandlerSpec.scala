package com.evernym.verity.metrics

import java.net.InetAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.`X-Real-Ip`
import akka.http.scaladsl.model.{HttpEntity, RemoteAddress}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.evernym.verity.ReqId
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.msg_tracer.resp_time_tracker.{NoResp, RespMode}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.testkit.{AkkaTestBasic, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.route_handlers.EndpointHandlerBase
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.msg_tracer.resp_time_tracker.MsgRespTimeRecorder
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.PackedMsgWrapper
import com.evernym.verity.actor.wallet.PackedMsg
import com.typesafe.config.Config
import kamon.Kamon
import kamon.metric.PeriodSnapshot
import kamon.module.MetricReporter

import scala.concurrent.{ExecutionContextExecutor, Future}


class MetricsEndpointHandlerSpec extends BasicSpec with ScalatestRouteTest {

  val metricsNames: Set[String] = Set(
    AS_ENDPOINT_HTTP_AGENT_MSG_COUNT,
    AS_ENDPOINT_HTTP_AGENT_MSG_SUCCEED_COUNT,
    AS_ENDPOINT_HTTP_AGENT_MSG_FAILED_COUNT)

  "An agency message route" - {
    "when metrics are turned on should" - {

      "count single success agency messages" in {
        val handler = new TestEndpointHandler

        val preMsgMetricsResult = extractMetrics(metricsNames)

        val bytes: Array[Byte] = Array[Byte]()
        Post("/agency/msg", HttpEntity.apply(bytes)).addHeader(`X-Real-Ip`(RemoteAddress(InetAddress.getLocalHost))) ~>
          handler.baseRoute ~> check {
            handled shouldBe true
        }

        val expectedMetricsResult = preMsgMetricsResult
          .incrementBy(AS_ENDPOINT_HTTP_AGENT_MSG_COUNT)
          .incrementBy(AS_ENDPOINT_HTTP_AGENT_MSG_SUCCEED_COUNT)

        val postMsgMetricsResult = extractMetrics(metricsNames)
        assert(expectedMetricsResult, postMsgMetricsResult)
      }

      "count single invalid content-type agency messages" in {
        val handler = new TestEndpointHandler

        val preMsgMetricsResult = extractMetrics(metricsNames)

        Post("/agency/msg").addHeader(`X-Real-Ip`(RemoteAddress(InetAddress.getLocalHost))) ~> handler.baseRoute ~> check {
          handled shouldBe false
        }
        val expectedMetricsResult = preMsgMetricsResult
          .incrementBy(AS_ENDPOINT_HTTP_AGENT_MSG_COUNT)
          .incrementBy(AS_ENDPOINT_HTTP_AGENT_MSG_FAILED_COUNT)

        val postMsgMetricsResult = extractMetrics(metricsNames)
        assert(expectedMetricsResult, postMsgMetricsResult)

      }

      "count single process failure agency messages" taggedAs (UNSAFE_IgnoreLog) in {
        val handler = new TestEndpointHandler
        handler.throwsException = true

        val preMsgMetricsResult = extractMetrics(metricsNames)

        val bytes: Array[Byte] = Array[Byte]()
        Post("/agency/msg", HttpEntity.apply(bytes)).addHeader(`X-Real-Ip`(RemoteAddress(InetAddress.getLocalHost))) ~>
          handler.baseRoute ~> check {
            handled shouldBe true
        }

        val expectedMetricsResult = preMsgMetricsResult
          .incrementBy(AS_ENDPOINT_HTTP_AGENT_MSG_COUNT)
          .incrementBy(AS_ENDPOINT_HTTP_AGENT_MSG_FAILED_COUNT)

        val postMsgMetricsResult = extractMetrics(metricsNames)
        assert(expectedMetricsResult, postMsgMetricsResult)

      }
    }
  }

  def extractMetrics(names: Set[String]): MetricsResult = {
    val report = TestReporter.awaitReport(Duration.ofSeconds(5))
    assert(report != null)

    val metrics = names.map(n => n -> {
      val gauges = report.gauges.filter(x => x.name == n)
      gauges.flatMap(x => x.instruments.map(_.value)).sum}
    ).toMap
    MetricsResult(metrics)
  }

  def assert(expectedMetricsResult: MetricsResult, actualMetricsResult: MetricsResult): Unit = {
    val expected = expectedMetricsResult.metrics
    val actual = actualMetricsResult.metrics
    expected.forall(e => e._2 == actual(e._1)) shouldBe true
  }

  def extractVal(names: Seq[String]): Map[String, Double] = {
    val report = TestReporter.awaitReport(Duration.ofSeconds(5))
    assert(report != null)

    names.map(n => n -> {
      val gauges = report.gauges.filter(x => x.name == n)
      gauges.flatMap(x => x.instruments.map(_.value)).sum}
    ).toMap
  }

  def checkForSuccess(expected: Map[String, Double], actual: Map[String, Double]): Boolean = {
    expected.forall(e => e._2 == actual(e._1))
  }
}

class TestEndpointHandler extends EndpointHandlerBase {
  var throwsException: Boolean = false

  override def platform: Platform = ???
  override def appConfig: AppConfig = new TestAppConfig()
  override implicit def executor: ExecutionContextExecutor = system.dispatcher
  override implicit def system: ActorSystem = AkkaTestBasic.system()

  override lazy val respTimeMetricsRecorder: MsgRespTimeRecorder = new MsgRespTimeRecorder {
    override def recordReqReceived(reqId: String, respMode: RespMode=NoResp): Unit = {}
    override def recordMetrics(reqId: ReqId, msgName: String, nextHop: String): Unit = {}
  }

  override def processPackedMsg(pmw: PackedMsgWrapper): Future[Any] = {
    Future {
      if (throwsException) {
        new RuntimeException("Testing Exception")
      } else {
        PackedMsg(Array[Byte]())
      }
    }
  }
}

object TestReporter {
  def awaitReport(timeout: Duration): PeriodSnapshot = {
    val tReporter = new TestReporter()
    Kamon.registerModule(UUID.randomUUID().toString, tReporter)

    val start = System.currentTimeMillis()
    while(true) {
      Thread.sleep(10)
      val curSnapshot = tReporter.lastSnapshot.get
      if(curSnapshot.isDefined){
        return curSnapshot.get
      } else {
        if (System.currentTimeMillis() - start > timeout.toMillis) {
          throw new RuntimeException("Timeout waiting for a snapshot")
        }
      }
    }
    throw new RuntimeException("Should not have except while loop without timing out")
  }
}

class TestReporter() extends MetricReporter {
  val lastSnapshot: AtomicReference[Option[PeriodSnapshot]] = new AtomicReference(None)

  override def reportPeriodSnapshot(snapshot: PeriodSnapshot): Unit = {
    if (lastSnapshot.get.isEmpty)
      lastSnapshot.set(Option(snapshot))
  }
  override def stop(): Unit = ()

  override def reconfigure(config: Config): Unit = ()
}

case class MetricsResult(metrics: Map[String, Double]) {

  def incrementBy(name: String, by: Int = 1): MetricsResult =
    this.copy(metrics = this.metrics.map(r => r._1 -> (if (r._1 == name) r._2 + by else r._2)))

  def decreaseBy(name: String, by: Int = 1): MetricsResult =
    this.copy(metrics = this.metrics.map(r => r._1 -> (if (r._1 == name) r._2 - by else r._2)))
}
