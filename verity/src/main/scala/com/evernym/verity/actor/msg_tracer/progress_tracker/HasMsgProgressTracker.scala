package com.evernym.verity.actor.msg_tracer.progress_tracker

import java.time.LocalDateTime
import akka.actor.{Actor, ActorRef}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.util2.ReqId
import com.evernym.verity.actor.agent.HasSingletonParentProxy
import com.evernym.verity.actor.{ForIdentifier, HasAppConfig}
import com.evernym.verity.actor.node_singleton.MsgProgressTrackerCache
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants.MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME
import com.evernym.verity.protocol.container.actor.ActorDriverGenParam
import com.evernym.verity.actor.msg_tracer.progress_tracker.RoutingEvent._
import com.evernym.verity.actor.node_singleton.MsgProgressTrackerCache.GLOBAL_TRACKING_ID
import com.evernym.verity.did.didcomm.v1.messages.{MsgId, MsgType, TypedMsgLike}
import com.evernym.verity.protocol.engine.{ProtoDef, ProtoRef}
import com.evernym.verity.protocol.engine.registry.ProtocolRegistry
import com.evernym.verity.util.HashAlgorithm.SHA256_trunc16
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.util.HashUtil


trait HasMsgProgressTracker
  extends HasSingletonParentProxy { this: Actor with HasAppConfig =>

  def appConfig: AppConfig
  def trackingIdParam: TrackingIdParam

  def recordSignalMsgTrackingEvent(reqId: ReqId, id: String, typedMsg: MsgType, detail: Option[String]): Unit = {
    recordSignalMsgTrackingEvent(reqId, id, typedMsg.toString, detail)
  }

  def recordSignalMsgTrackingEvent(reqId: ReqId, id: String, msgTypeStr: String, detail: Option[String]): Unit = {
    recordOutMsgEvent(reqId, MsgEvent(id, msgTypeStr + " [signal]", detail))
  }

  def recordLocallyHandledSignalMsgTrackingEvent(reqId: ReqId, msgTypeStr: String): Unit = {
    recordOutMsgEvent(reqId, MsgEvent("n/a", msgTypeStr + " [signal]", "to-be-handled-locally"))
  }

  def recordProtoMsgTrackingEvent(reqId: ReqId, id: String, typedMsg: MsgType, detail: Option[String]): Unit = {
    recordOutMsgEvent(reqId, MsgEvent(id, typedMsg.toString + " [proto]", detail))
  }

  def recordInMsgTrackingEvent(reqId: ReqId, id: String, typedMsg: TypedMsgLike, detail: Option[String], protoDef: ProtoDef): Unit = {
    val protoMsgType =
      if (protoDef.msgFamily.isControlMsg(typedMsg.msg)) "control"
      else "proto"
    recordInMsgEvent(reqId, MsgEvent(id, typedMsg.msgType.toString + s" [$protoMsgType]", detail))
  }

  def msgTypeStr(protoRef: ProtoRef, msg: Any)(implicit protocolRegistry: ProtocolRegistry[ActorDriverGenParam]): String = {
    val protoDef = protocolRegistry.find(protoRef).map(_.protoDef).get
    msgTypeStr(protoDef, msg)
  }

  def msgTypeStr(protoDef: ProtoDef, msg: Any): String = {
    try {
      protoDef.msgFamily.msgType(msg.getClass).toString
    } catch {
      case _: NoSuchElementException =>
        s"${msg.getClass.getSimpleName} (${protoDef.msgFamily})"
    }
  }

  def recordRoutingChildEvent(reqId: ReqId,
                              childEvent: ChildEvent): Unit = {
    recordRoutingChildEvents(reqId, List(childEvent))
  }

  private def recordRoutingChildEvents(reqId: ReqId,
                                       childEvents: List[ChildEvent]): Unit = {
    sendToMsgTracker(RecordRoutingChildEvents(reqId, ROUTING_EVENT_ID_PROCESSING, childEvents))
  }

  def recordInMsgChildEvent(reqId: ReqId, inMsgId: MsgId, childEvent: ChildEvent): Unit = {
    recordInMsgChildEvents(reqId, inMsgId, List(childEvent))
  }
  def recordInMsgChildEvents(reqId: ReqId, inMsgId: MsgId, childEvents: List[ChildEvent]): Unit = {
    sendToMsgTracker(RecordInMsgChildEvents(reqId, inMsgId, childEvents))
  }

  def recordOutMsgChildEvent(reqId: ReqId, outMsgId: MsgId, childEvent: ChildEvent): Unit = {
    recordOutMsgChildEvents(reqId, outMsgId, List(childEvent))
  }
  def recordOutMsgChildEvents(reqId: ReqId, outMsgId: MsgId, childEvents: List[ChildEvent]): Unit = {
    sendToMsgTracker(RecordOutMsgChildEvents(reqId, outMsgId, childEvents))
  }

  def recordArrivedRoutingEvent(reqId: ReqId, startTime: LocalDateTime, detail: String): Unit = {
    sendToMsgTracker(RecordRoutingEvent(reqId,
      RoutingEvent(id = ROUTING_EVENT_ID_ARRIVED, detail = Option(detail), recordedAt = startTime)))
  }
  def recordInMsgEvent(reqId: ReqId, event: MsgEvent): Unit = {
    sendToMsgTracker(RecordInMsgEvent(reqId, event))
  }
  def recordOutMsgEvent(reqId: ReqId, event: MsgEvent): Unit = {
    sendToMsgTracker(RecordOutMsgEvent(reqId, event))
  }
  def recordOutMsgDeliveryEvent(msgId: MsgId): Unit = {
    sendToMsgTracker(RecordOutMsgDeliveryEvents(msgId, List.empty))
  }
  def recordOutMsgDeliveryEvent(msgId: MsgId, event: MsgEvent): Unit = {
    sendToMsgTracker(RecordOutMsgDeliveryEvents(msgId, List(event)))
  }
  def recordOutMsgDeliveryEvents(msgId: MsgId, events: List[MsgEvent]): Unit = {
    sendToMsgTracker(RecordOutMsgDeliveryEvents(msgId, events))
  }

  def childEventWithDetail(msg: String, sndr: ActorRef = sender()): ChildEvent = {
    ChildEvent(msg, detail = Option(s"sender: ${sndr.path}, self: ${self.path}"))
  }

  private def sendToMsgTracker(cmd: Any): Unit = {
    candidateTrackingIds.foreach { trackingId =>
      if (MsgProgressTrackerCache(context.system).isTracked(trackingId)) {
        val finalCmd = cmd match {
          case rre: RecordRoutingEvent => rre.withDetailAppended(extraDetail)
          case other                   => other
        }
        msgProgressTrackerRegion ! ForIdentifier(trackingId, finalCmd)
      }
    }
  }

  private def candidateTrackingIds: Set[String] = defaultTrackingIds ++ Option(GLOBAL_TRACKING_ID)

  lazy val extraDetail: String = trackingIdParam.domainTrackingDetail + trackingIdParam.relTrackingDetail.getOrElse("")

  private lazy val defaultTrackingIds = Set(trackingIdParam.sha256DomainId) ++ trackingIdParam.sha256MyRelId

  private lazy val msgProgressTrackerRegion: ActorRef =
    ClusterSharding(context.system)
      .shardRegion(MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME)
}

case class TrackingIdParam(domainId: String, myId: Option[String], theirId: Option[String]) {
  lazy val sha256DomainId: String = HashUtil.hash(SHA256_trunc16)(domainId).hex
  lazy val sha256MyRelId: Option[String] = myId.map(HashUtil.hash(SHA256_trunc16)(_).hex)
  lazy val sha256TheirRelId: Option[String] = theirId.map(HashUtil.hash(SHA256_trunc16)(_).hex)

  lazy val domainTrackingDetail = s"domainTrackingId: $sha256DomainId"
  lazy val relTrackingDetail: Option[MsgId] =
    if (myId.contains(domainId)) None
    else {
      sha256MyRelId.map { myId =>
        s"\nmyRelTrackingId: $myId" +
          sha256TheirRelId
            .map { theirId => s"\ntheirRelTrackingId: $theirId" }
            .getOrElse("")
      } orElse None
    }
}