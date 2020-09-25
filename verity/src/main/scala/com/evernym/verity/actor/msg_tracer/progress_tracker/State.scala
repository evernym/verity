package com.evernym.verity.actor.msg_tracer.progress_tracker

import java.time.Instant

import com.evernym.verity.ReqId
import com.evernym.verity.protocol.engine.{MsgFamilyName, MsgFamilyVersion, PinstId, ProtoRef}
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

case class State(reqId: ReqId,
                 receivedAt: Instant = Instant.now(),
                 summary: Summary = Summary(),
                 events: Option[List[EventWrapper]] = None,
                 finishedAt: Option[Instant] = None,
                 failedAt: Option[Instant] = None,
                 clientIpAddress: Option[String] = None) {

  def updateRequestDetail(eventParam: EventParam): State = {

    val (updatedReqDetail, context, status) = eventParam.event match {

      case e: EventReceivedByHttpEndpoint => (this, e.context, e.context)

      case e: EventRouteGetStarted        => (this, e.context, e.context)

      case e: EventRouteGetFinished       => (this, e.context, e.context)

      case e: EventProtocolMsgProgress    =>
        (copy(
          summary = summary
            .copy(
              inMsgParam = summary.updateInMsgParam(e.inMsgParam),
              outMsgParam = summary.updateOutMsgParam(e.outMsgParam),
              protoParam = summary.updateProtoParam(e.protoParam)),
          finishedAt = Option(Instant.now())),
          e.context, e.context)

      case erba: EventReceivedByAgent                =>
        (copy(
          summary = summary
            .copy(
              trackingParam = summary.updateTrackingParam(erba.trackingParam),
              inMsgParam = summary.updateInMsgParam(erba.inMsgParam))),
          erba.context, erba.context)

      case euba: EventUnpackedByAgent                =>
        (copy(
          summary = summary
            .copy(inMsgParam = summary.updateInMsgParam(euba.inMsgParam))),
          euba.context, euba.context)

      case ermps: EventInMsgProcessingStarted        =>
        (copy(
          summary = summary.copy(
            trackingParam = summary.updateTrackingParam(ermps.trackingParam),
            inMsgParam = summary.updateInMsgParam(ermps.inMsgParam),
            protoParam = summary.updateProtoParam(ermps.protoParam))),
          ermps.context, ermps.context)

      case ermps: EventOutMsgPackagingStarted        =>
        (copy(
          summary = summary
            .copy(
              outMsgParam = summary.updateOutMsgParam(MsgParam(msgId = ermps.outMsgId)))),
          ermps.context, ermps.context)

      case ermps: EventOutMsgPackagingFinished       =>
        (copy(
          summary = summary.copy(
            outMsgParam = summary.updateOutMsgParam(ermps.outMsgParam))),
          ermps.context, ermps.context)

      case e: EventOutMsgSentToNextHop =>
        (copy(finishedAt = Option(Instant.now())), e.context, e.status)

      case e: EventOutMsgSendingFailed =>
        (copy(failedAt = Option(Instant.now())), e.context, e.status)

    }

    val oldEvents = events.getOrElse(List.empty)
    val timeTakenInMillis = oldEvents.headOption.map(oet => eventParam.atEpochMillis - oet.atEpochMillis)
    val newEvents = Option(EventWrapper(eventParam, context, status, eventParam.atEpochMillis, timeTakenInMillis) +: oldEvents)
    updatedReqDetail
      .copy(
        summary =
          updatedReqDetail.summary
            .copy(status=Option(status))
            .updateInMsgType()
            .updateOutMsgType(),
        events = newEvents
      )
  }
}

case class Summary(trackingParam: TrackingParam = TrackingParam(),
                   inMsgParam: MsgParam = MsgParam(),
                   outMsgParam: MsgParam = MsgParam(),
                   protoParam: ProtoParam = ProtoParam(),
                   status: Option[String] = None) {

  def updateInMsgParam(newMsgParam: MsgParam): MsgParam = {
    inMsgParam.merge(newMsgParam)
  }

  def updateInMsgType(): Summary = {
    copy(inMsgParam = inMsgParam.copy(msgType = msgType(inMsgParam.msgName, protoParam)))
  }

  def updateOutMsgType(): Summary = {
    copy(outMsgParam = outMsgParam.copy(msgType = msgType(outMsgParam.msgName, protoParam)))
  }

  def updateOutMsgParam(newMsgParam: MsgParam): MsgParam = {
    outMsgParam.merge(newMsgParam)
  }

  def updateTrackingParam(newTrackingParam: TrackingParam): TrackingParam = {
    trackingParam.merge(newTrackingParam)
  }

  def updateProtoParam(newProtoParam: ProtoParam): ProtoParam = {
    protoParam.merge(newProtoParam)
  }

  def msgType(msgName: Option[String], protoParam: ProtoParam): Option[String] = {
    (msgName, protoParam.familyName, protoParam.familyVersion) match {
      case (Some(mn), Some(fn), Some(fv)) =>
        val protoRef = ProtoRef(fn, fv)
        com.evernym.verity.protocol.protocols.availableProtocols.find(protoRef).map { e =>
          e.protoDef.msgFamily.msgCategory(mn).getOrElse("System/Legacy")
        }
      case _ => None
    }
  }
}


case class EventReceivedByHttpEndpoint(endpoint: String) {
  val context = s"received-by-http-endpoint ($endpoint)"
}

case class EventRouteGetStarted(additionalContext: String) {
  val context = s"route-get-started ($additionalContext)"
}

case class EventRouteGetFinished(additionalContext: String) {
  val context = s"route-get-finished ($additionalContext)"
}

case class EventReceivedByAgent(additionalContext: String,
                                trackingParam: TrackingParam,
                                inMsgParam: MsgParam) {
  val context = s"received-by-agent ($additionalContext)"
}

case class EventUnpackedByAgent(additionalContext: String, inMsgParam: MsgParam) {
  val context = s"unpacked-by-agent ($additionalContext)" +
    inMsgParam.msgType.map(mn => s" [$mn]").getOrElse("")
}

case class EventInMsgProcessingStarted(trackingParam: TrackingParam,
                                       inMsgParam: MsgParam,
                                       protoParam: ProtoParam) {
  val context = "incoming-msg-processing-started"
}

case class EventProtocolMsgProgress(additionalContext: String,
                                    inMsgParam: MsgParam,
                                    outMsgParam: MsgParam,
                                    protoParam: ProtoParam)  {
  val context = s"$additionalContext"
}

case class EventOutMsgPackagingStarted(outMsgId: Option[String]=None) {
  val context = "outgoing-msg-packaging-started"
}

case class EventOutMsgPackagingFinished(outMsgParam: MsgParam)  {
  val context = "outgoing-msg-packaging-finished"
}


case class EventOutMsgSentToNextHop(hop: String) {
  val context = "sent-to-next-hop"
  def status: String = context + s" ($hop)"
}

case class EventOutMsgSendingFailed(hop: String, errorMsg: String)  {
  val context = "sending-to-next-hop-failed"
  def status: String = context + s" [$hop] [error-msg: $errorMsg]"
}

case class EventWrapper(event: Any,
                        context: String,
                        status: String,
                        @JsonDeserialize(contentAs = classOf[Long]) atEpochMillis: Long = Instant.now().toEpochMilli,
                        @JsonDeserialize(contentAs = classOf[Long]) timeTakenInMillis: Option[Long]=None)

case class MsgParam(msgId: Option[String]=None, msgName: Option[String]=None,
                    msgType: Option[String]=None, replyToMsgId: Option[String]=None) {

  def merge(nmp: MsgParam): MsgParam = {
    val mid   = msgId orElse nmp.msgId
    val mn    = msgName orElse nmp.msgName
    val rtmid = replyToMsgId orElse nmp.replyToMsgId
    MsgParam(msgId = mid, msgName = mn, msgType = msgType, replyToMsgId = rtmid)
  }
}

case class TrackingParam(domainTrackingId: Option[String]=None, relTrackingId: Option[String]=None, threadId: Option[String]=None) {
  def merge(ntp: TrackingParam): TrackingParam = {
    val dti = domainTrackingId orElse ntp.domainTrackingId
    val rti = ntp.relTrackingId orElse relTrackingId
    val tid = threadId orElse ntp.threadId
    TrackingParam(dti, rti, tid)
  }
}

object ProtoParam {
  def apply(pinstId: String, familyName: MsgFamilyName, familyVersion: MsgFamilyVersion): ProtoParam = {
    ProtoParam(Option(pinstId), Option(familyName), Option(familyVersion))
  }
}
case class ProtoParam(pinstId: Option[PinstId]=None, familyName: Option[MsgFamilyName]=None, familyVersion: Option[MsgFamilyVersion]=None) {

  def merge(newProtoParam: ProtoParam): ProtoParam = {
    val pid = pinstId orElse newProtoParam.pinstId
    val fn = familyName orElse newProtoParam.familyName
    val fv = familyVersion orElse newProtoParam.familyVersion
    ProtoParam(pid, fn, fv)
  }
}