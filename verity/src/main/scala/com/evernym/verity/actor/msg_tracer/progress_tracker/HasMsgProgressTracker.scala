package com.evernym.verity.actor.msg_tracer.progress_tracker

import akka.actor.{Actor, ActorRef}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.ReqId
import com.evernym.verity.actor.{ForIdentifier, SendCmdToAllNodes, StartProgressTracking}
import com.evernym.verity.actor.node_singleton.{MsgProgressTrackerCache, TrackingParam}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.ActorNameConstants.{MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME, SINGLETON_PARENT_PROXY}
import com.evernym.verity.protocol.actor.ActorDriverGenParam
import com.evernym.verity.actor.node_singleton.MsgProgressTrackerCache.GLOBAL_TRACKING_ID
import com.evernym.verity.protocol.engine.{MsgId, MsgType, ProtoDef, ProtoRef, ProtocolRegistry, TypedMsgLike}
import com.evernym.verity.util.HashAlgorithm.SHA256_trunc16
import com.evernym.verity.util.HashUtil.byteArray2RichBytes
import com.evernym.verity.util.HashUtil
import com.evernym.verity.util.Util.getActorRefFromSelection


trait HasMsgProgressTracker { this: Actor =>

  def appConfig: AppConfig
  def selfRelTrackingId: String
  def pairwiseRelTrackingIds: List[String]

  lazy val sha256SelfRelTrackingId: String = sha256HashedTrackingId(selfRelTrackingId)
  lazy val sha256PairwiseRelTrackingId: Option[String] = {
    Option(sha256HashedTrackingId(pairwiseRelTrackingIds.sorted.mkString("")))
  }

  def sha256HashedTrackingId(str: String): String = HashUtil.hash(SHA256_trunc16)(str).hex

  def recordSignalMsgTrackingEvent(reqId: ReqId, id: String, typedMsg: MsgType, detail: Option[String]): Unit = {
    recordSignalMsgTrackingEvent(reqId, id, typedMsg.toString, detail)
  }

  def recordSignalMsgTrackingEvent(reqId: ReqId, id: String, msgTypeStr: String, detail: Option[String]): Unit = {
    recordOutMsgEvent(reqId, MsgEvent(id, msgTypeStr + " [signal]", detail))
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
        s"${msg.getClass.getSimpleName} (${protoDef.msgFamily.protoRef.toString})"
    }
  }

  def recordRoutingChildEvent(reqId: ReqId, childEvent: ChildEvent): Unit = {
    recordRoutingChildEvents(reqId, List(childEvent))
  }
  def recordRoutingChildEvents(reqId: ReqId, childEvents: List[ChildEvent]): Unit = {
    sendToMsgTracker(RecordRoutingChildEvents(reqId, childEvents))
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

  def recordRoutingEvent(reqId: ReqId, detail: String): Unit = {
    sendToMsgTracker(RecordRoutingEvent(reqId, RoutingEvent(detail = Option(detail))))
  }
  def recordInMsgEvent(reqId: ReqId, event: MsgEvent): Unit = {
    sendToMsgTracker(RecordInMsgEvent(reqId, event))
  }
  def recordOutMsgEvent(reqId: ReqId, event: MsgEvent): Unit = {
    sendToMsgTracker(RecordOutMsgEvent(reqId, event))
  }
  def recordOutMsgDeliveryEvent(msgId: MsgId, event: MsgEvent): Unit = {
    sendToMsgTracker(RecordOutMsgDeliveryEvent(msgId, event))
  }

  def childEventWithDetail(msg: String, sndr: ActorRef = sender()): ChildEvent = {
    ChildEvent(msg, detail = Option(s"sender: $sndr, self: ${self.path}"))
  }

  private def sendToMsgTracker(cmd: Any): Unit = {
    candidateTrackingIds.foreach { trackingId =>
      if (MsgProgressTrackerCache.isTracked(trackingId)) {
        val finalCmd = cmd match {
          case rre: RecordRoutingEvent if MsgProgressTracker.isGlobalOrIpAddress(trackingId) =>
            rre.copy(event = rre.event.copy(detail =
              Option(rre.event.detail.map(d => s"$d ").getOrElse("") + s"(trackingDetail => $extraDetail)")))
          case other                   => other
        }
        msgProgressTrackerRegion ! ForIdentifier(trackingId, finalCmd)
      }
    }
  }

  private def candidateTrackingIds: Set[String] = {

    val ipAddressTrackingIds =
      MsgProgressTrackerCache
        .allIdsBeingTracked
        .trackedIds
        .filter(tp => defaultRelTrackingIds.contains(tp.trackingId))
        .flatMap(_.ipAddress)

    val globalTrackingId = Option(GLOBAL_TRACKING_ID)

    defaultRelTrackingIds ++ ipAddressTrackingIds ++ globalTrackingId
  }

  def checkToStartIpAddressBasedTracking(ipAddress: String): Unit = {
    if (MsgProgressTrackerCache.isTracked(ipAddress)) {
      defaultRelTrackingIds.foreach { trackingId =>
        if (! MsgProgressTrackerCache.isTracked(trackingId))
          singletonParentProxyActor ! SendCmdToAllNodes(StartProgressTracking(
            TrackingParam(trackingId, Option(ipAddress))))
      }
    }
  }

  lazy val extraDetail: String = {
    val selfRelDetail = s"selfRelTrackingId: $sha256SelfRelTrackingId"
    if (sha256PairwiseRelTrackingId.contains(sha256SelfRelTrackingId)) {
      selfRelDetail
    } else {
      selfRelDetail + sha256PairwiseRelTrackingId.map(id => s", pairwiseRelTrackingId: $id").getOrElse("")
    }
  }

  private lazy val defaultRelTrackingIds = Set(sha256SelfRelTrackingId) ++ sha256PairwiseRelTrackingId


  private lazy val msgProgressTrackerRegion: ActorRef =
    ClusterSharding(context.system)
      .shardRegion(MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME)

  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)
}
