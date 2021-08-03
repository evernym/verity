package com.evernym.verity.protocol.protocols

import java.time.ZoneId
import akka.actor.Actor.Receive
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status._
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.actor.agent.{Msg, MsgDeliveryDetail, PayloadMetadata, PayloadWrapper}
import com.evernym.verity.actor.agent.user.MsgHelper
import com.evernym.verity.actor.agent.{AttrName, AttrValue}
import com.evernym.verity.actor.{Evt, MsgAnswered, MsgCreated, MsgDeliveryStatusUpdated, MsgDetailAdded, MsgExpirationTimeUpdated, MsgPayloadStored, MsgStatusUpdated}
import com.evernym.verity.did.DID
import com.evernym.verity.protocol.engine.{MsgId, MsgName, RefMsgId}
import com.evernym.verity.util.TimeZoneUtil._


class ConnectionMsgState {

  type Payload = Array[Byte]
  type Seconds = Int
  type Destination = String

  /**
   * mapping between MsgId and Msg
   */
  private var msgs: Map[MsgId, Msg] = Map.empty

  /**
   * mapping between MsgId and associated binary payload (which needs to be delivered to next hop)
   * there is no specific reason to keep this 'payload' separate instead of keeping
   * it in 'Msg' class itself (but this is what it is right now)
   */
  private var msgPayloads: Map[MsgId, PayloadWrapper] = Map.empty

  /**
   * mapping between MsgId and associated attributes
   * during message forwarding there are certain attributes sent (like sender name and logo url)
   * which is then used during sending push notification (on cas side)
   */
  private var msgDetails: Map[MsgId, Map[AttrName, AttrValue]] = Map.empty

  /**
   * mapping between MsgId and "various" (could be more than one for different destinations) delivery status
   * keeps delivery status (failed/successful etc) for different delivery destinations (push notification, http endpoint etc)
   * for given message
   */
  private var msgDeliveryStatus: Map[MsgId, Map[Destination, MsgDeliveryDetail]] = Map.empty

  /**
   * mapping between MsgName (message type name, like: connection req, cred etc)
   * and it's expiration time in seconds
   */
  private var msgExpirationTime: Map[MsgName, Seconds] = Map.empty

  /**
   * imaging below collection of messages (each of the below line is a message record with different fields)
   *  uid1, conReq,       refMsgId=uid2 ,...
   *  uid2, conReqAnswer, ...           ,...
   *
   *
   * for above example use case, the 'refMsgIdToMsgId' mapping will look like this:
   *  uid2 -> uid1
   */
  private var refMsgIdToMsgId: Map[RefMsgId, MsgId] = Map.empty

  private var unseenMsgIds: Set[MsgId] = Set.empty
  private var seenMsgIds: Set[MsgId] = Set.empty

  def getMsgOpt(uid: MsgId): Option[Msg] = msgs.get(uid)

  def getMsgPayload(uid: MsgId): Option[PayloadWrapper] = msgPayloads.get(uid)

  def getMsgReq(uid: MsgId): Msg = getMsgOpt(uid).getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"msg not found with uid: $uid")))

  def getReplyToMsgId(msgId: MsgId): Option[MsgId] = {
    refMsgIdToMsgId.get(msgId)
  }

  def getReplyToMsg(msgId: MsgId): Option[Msg] = {
    getReplyToMsgId(msgId).flatMap(msgs.get)
  }

  def getMsgDeliveryStatus(uid: MsgId): Map[String, MsgDeliveryDetail] = {
    msgDeliveryStatus.getOrElse(uid, Map.empty)
  }

  def getMsgDetails(uid: MsgId): Map[AttrName, AttrValue] = {
    msgDetails.getOrElse(uid, Map.empty)
  }

  def getMsgExpirationTime(forMsgType: String): Option[Seconds] = {
    msgExpirationTime.get(forMsgType)
  }

  def checkIfMsgAlreadyNotExists(msgId: MsgId): Unit = {
    if (getMsgOpt(msgId).isDefined) {
      throw new BadRequestErrorException(ALREADY_EXISTS.statusCode, Option("msg with uid already exists: " + msgId))
    }
  }

  implicit val zoneId: ZoneId = UTCZoneId

  def msgEventReceiver: Receive = {
    case mc: MsgCreated =>
      val receivedOrders = mc.thread.map(th => th.receivedOrders.map(ro => ro.from -> ro.order).toMap).getOrElse(Map.empty)
      val msgThread = mc.thread.map(th =>
        com.evernym.verity.actor.agent.Thread(
          Evt.getOptionFromValue(th.id),
          Evt.getOptionFromValue(th.parentId),
          Option(th.senderOrder),
          receivedOrders
        )
      )
      val msg = Msg(
        mc.typ,
        mc.senderDID,
        mc.statusCode,
        mc.creationTimeInMillis,
        mc.lastUpdatedTimeInMillis,
        Evt.getOptionFromValue(mc.refMsgId),
        msgThread,
        mc.sendMsg
      )
      msgs += mc.uid -> msg
      updateMsgIndexes(mc.uid, msg)

    case ma: MsgAnswered =>
      msgs.get(ma.uid).foreach { msg =>
        val updated = msg.copy(statusCode = ma.statusCode, refMsgId = Option(ma.refMsgId),
          lastUpdatedTimeInMillis = ma.lastUpdatedTimeInMillis)
        msgs += ma.uid -> updated
        updateMsgIndexes(ma.uid, updated)
      }

    case msu: MsgStatusUpdated =>
      msgs.get(msu.uid).foreach { msg =>
        val updated = msg.copy(statusCode = msu.statusCode, lastUpdatedTimeInMillis =
          msu.lastUpdatedTimeInMillis)
        msgs += msu.uid -> updated
        updateMsgIndexes(msu.uid, updated)
      }

    case mdsu: MsgDeliveryStatusUpdated =>
      if (mdsu.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode) {
        msgs.get(mdsu.uid).filter(_.statusCode == MSG_STATUS_CREATED.statusCode).foreach { msg =>
          val updated = msg.copy(statusCode = MSG_STATUS_SENT.statusCode)
          msgs += mdsu.uid -> updated
        }
        msgDeliveryStatus -= mdsu.uid
      } else {
        val emds = msgDeliveryStatus.getOrElse(mdsu.uid, Map.empty)
        val newMds = MsgDeliveryDetail(mdsu.statusCode, Evt.getOptionFromValue(mdsu.statusDetail),
          mdsu.failedAttemptCount, mdsu.lastUpdatedTimeInMillis)
        val nmds = emds ++ Map(mdsu.to -> newMds)
        msgDeliveryStatus += mdsu.uid -> nmds
      }

    case mda: MsgDetailAdded =>
      val emd = msgDetails.getOrElse(mda.uid, Map.empty)
      val nmd = emd ++ Map(mda.name -> mda.value)
      msgDetails += mda.uid -> nmd

    case meps: MsgPayloadStored =>
      val metadata = meps.payloadContext.map { pc => PayloadMetadata(pc.msgType, pc.msgPackFormat) }
      msgPayloads += meps.uid -> PayloadWrapper(meps.payload.toByteArray, metadata)

    case me: MsgExpirationTimeUpdated =>
      msgExpirationTime = msgExpirationTime ++ Map(me.msgType -> me.timeInSeconds)
  }

  def buildMsgCreatedEvt(msgId: MsgId,
                         mType: String,
                         senderDID: DID,
                         sendMsg: Boolean,
                         msgStatus: String,
                         threadOpt: Option[Thread],
                         LEGACY_refMsgId: Option[MsgId]=None): MsgCreated = {
    checkIfMsgAlreadyNotExists(msgId)
    MsgHelper.buildMsgCreatedEvt(
      msgId,
      mType,
      senderDID,
      sendMsg,
      msgStatus,
      threadOpt,
      LEGACY_refMsgId
    )
  }

  def updateMsgIndexes(msgId: MsgId, msg: Msg): Unit = {
    if (msg.statusCode == MSG_STATUS_RECEIVED.statusCode) {
      unseenMsgIds += msgId
    } else if (MsgHelper.seenMsgStatusCodes.contains(msg.statusCode)) {
      seenMsgIds += msgId
      unseenMsgIds -= msgId
    }
    msg.refMsgId.foreach { refMsgId =>
      refMsgIdToMsgId += refMsgId -> msgId
    }
  }
}
