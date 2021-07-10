package com.evernym.verity.msg_tracer.resp_time_tracker

import java.time.Instant

import com.evernym.verity.actor.msg_tracer.resp_time_tracker._
import com.evernym.verity.actor.base.{AlreadyDone, Done}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.{METRICS_LATENCY_RECORDING_HISTOGRAM, METRICS_LATENCY_RECORDING_SPAN}
import kamon.Kamon

/**
 * class responsible to keep state related to one msg tracking
 * @param appConfig
 */
class MsgRespTimeMetricsRecorder(appConfig: AppConfig) {

  private var reqReceivedAtEpochMillis: Option[Long] = None
  private var respMode: Option[RespMode] = None

  def execute: PartialFunction[Any, Any] = {
    case mtm: MsgTracerActorMsg =>
      val resp = handleInComingCommands(mtm)
      if (respMode.contains(SendBackResp)) {
        resp
      }
  }

  private def handleInComingCommands: PartialFunction[Any, Any] = {
    case _: ReqReceived if respMode.isDefined => AlreadyDone
    case rr: ReqReceived                      => handleReqReceived(rr)
    case cm: CaptureMetrics                   => handleCaptureMetrics(cm)
  }

  private def handleCaptureMetrics(cm: CaptureMetrics): Any = {
    if (reqReceivedAtEpochMillis.isDefined) {
      updateSpanLatencyMetrics(cm.msgName, cm.nextHop)
      updateHistogramMetrics(cm.msgName, cm.nextHop)
    }
    Done
  }

  private def handleReqReceived(rr: ReqReceived): Any = {
    reqReceivedAtEpochMillis = Option(rr.atEpochMillis)
    respMode = Option(rr.respMode)
    Done
  }

  private def updateSpanLatencyMetrics(msgTypeName: String, nextHop: String): Unit = {
    if (isSpanLatencyRecordingEnabled) {
      val tags = List(MetricTag(TAG_NAME_MSG_TYPE, msgTypeName), MetricTag(TAG_NAME_NEXT_HOP, nextHop))
      val span = Kamon.spanBuilder("msg-latency").start(
        Instant.ofEpochMilli(startedTimeEpochMillis))
      val taggedSpan = tags.foldLeft(span) { case (sp, mt) =>
        sp.tagMetrics(mt.name, mt.value.toString)
      }
      taggedSpan.finish(Instant.now())
    }
  }

  private def updateHistogramMetrics(msgTypeName: String, nextHop: String): Unit = {
    if (isHistogramLatencyRecordingEnabled) {
      val metricsName = "histogram.processing.time.millis"
      Kamon
        .histogram(metricsName)
        .withTag(TAG_NAME_MSG_TYPE, msgTypeName)
        .withTag(TAG_NAME_NEXT_HOP, nextHop)
        .record(timeTakenInMillis)
    }
  }

  val TAG_NAME_MSG_TYPE = "msg-type"
  val TAG_NAME_NEXT_HOP = "next-hop"

  def isSendBackResp: Boolean = respMode.contains(SendBackResp)

  lazy val startedTimeEpochMillis: Long = reqReceivedAtEpochMillis.get

  lazy val timeTakenInMillis: Long = Instant.now().toEpochMilli - startedTimeEpochMillis

  lazy val isHistogramLatencyRecordingEnabled: Boolean =
    appConfig
      .getBooleanOption(METRICS_LATENCY_RECORDING_HISTOGRAM)
      .getOrElse(true)

  lazy val isSpanLatencyRecordingEnabled: Boolean =
    appConfig
      .getBooleanOption(METRICS_LATENCY_RECORDING_SPAN)
      .getOrElse(false)   //due to serialization issue, default value is false
}
