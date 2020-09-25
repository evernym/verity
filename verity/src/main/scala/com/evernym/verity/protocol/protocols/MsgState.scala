package com.evernym.verity.protocol.protocols

import java.time.ZoneId
import java.time.temporal.ChronoUnit

import akka.actor.Actor.Receive
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.{AttrName, AttrValue}
import com.evernym.verity.actor.{Evt, MsgAnswered, MsgCreated, MsgDeliveryStatusUpdated, MsgDetailAdded, MsgExpirationTimeUpdated, MsgPayloadStored, MsgReceivedOrdersDetail, MsgStatusUpdated, MsgThreadDetail}
import com.evernym.verity.actor.agent.msghandler.outgoing.PayloadMetadata
import com.evernym.verity.agentmsg.msgfamily.pairwise.{GetMsgsReqMsg, MsgThread}
import com.evernym.verity.protocol.engine.{DID, MsgId, MsgName, RefMsgId}
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.util.Util.checkIfDIDLengthIsValid


class MsgState(_msgDeliveryState: Option[MsgDeliveryState]=None) {

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
  private var msgDeliveryStatus: Map[MsgId, Map[Destination, MsgDeliveryStatus]] = Map.empty

  /**
   * mapping between MsgName (message type name, like: connection req, cred etc)
   * and it's expiration time in seconds
   */
  private var msgExpirationTime: Map[MsgName, Seconds] = Map.empty

  /**
   * this is used to report message delivery status (successful, failed etc) to
   * this 'msgDeliveryState' object which then has logic to decide which of those messages
   * will be candidate for retry
   * @return
   */
  def msgDeliveryState: Option[MsgDeliveryState] = _msgDeliveryState

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

  def getMsgPayloadReq(uid: MsgId): PayloadWrapper = getMsgPayload(uid).getOrElse(
    throw new InternalServerErrorException(DATA_NOT_FOUND.statusCode, Option("payload not found"))
  )

  def getMsgPayloadMetadata(uid: MsgId): Option[PayloadMetadata] = getMsgPayload(uid).flatMap(_.metadata)

  def getMsgReq(uid: MsgId): Msg = getMsgOpt(uid).getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"msg not found with uid: " + uid)))

  def getReplyToMsgId(msgId: MsgId): Option[MsgId] = {
    refMsgIdToMsgId.get(msgId)
  }

  def getReplyToMsg(msgId: MsgId): Option[Msg] = {
    getReplyToMsgId(msgId).flatMap(msgs.get)
  }

  def getMsgDeliveryStatus(uid: MsgId): Map[String, MsgDeliveryStatus] = {
    msgDeliveryStatus.getOrElse(uid, Map.empty)
  }

  def getMsgDetails(uid: MsgId): Map[AttrName, AttrValue] = {
    msgDetails.getOrElse(uid, Map.empty)
  }

  def getMsgExpirationTime(forMsgType: String): Option[Seconds] = {
    msgExpirationTime.get(forMsgType)
  }

  def getMsgs(gmr: GetMsgsReqMsg): List[MsgDetail] = {
    val msgIds = gmr.uids.getOrElse(List.empty).map(_.trim).toSet
    val statusCodes = gmr.statusCodes.getOrElse(List.empty).map(_.trim).toSet
    val filteredMsgs = {
      if (msgIds.isEmpty && statusCodes.size == 1 && statusCodes.head == MSG_STATUS_RECEIVED.statusCode) {
        unseenMsgIds.map(mId => mId -> getMsgOpt(mId))
          .filter(_._2.nonEmpty)
          .map(r => r._1 -> r._2.get)
          .toMap
      } else {
        val uidFilteredMsgs = if (msgIds.nonEmpty) {
          msgIds.map(mId => mId -> getMsgOpt(mId))
            .filter(_._2.nonEmpty)
            .map(r => r._1 -> r._2.get)
            .toMap
        } else msgs
        if (statusCodes.nonEmpty) uidFilteredMsgs.filter(m => statusCodes.contains(m._2.statusCode))
        else uidFilteredMsgs
      }
    }
    filteredMsgs.map { case (uid, msg) =>
      val payloadWrapper = if (gmr.excludePayload.contains(YES)) None else msgPayloads.get(uid)
      val payload = payloadWrapper.map(_.msg)
      MsgDetail(uid, msg.`type`, msg.senderDID, msg.statusCode, msg.refMsgId, msg.thread, payload, Set.empty)
    }.toList
  }

  def checkIfMsgExists(uidOpt: Option[MsgId]): Unit = {
    uidOpt.foreach { uid =>
      if (getMsgOpt(uid).isEmpty) {
        throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"msg not found with uid: " + uid))
      }
    }
  }

  def checkIfMsgAlreadyNotExists(msgId: MsgId): Unit = {
    if (getMsgOpt(msgId).isDefined) {
      throw new BadRequestErrorException(ALREADY_EXISTS.statusCode, Option("msg with uid already exists: " + msgId))
    }
  }

  implicit val zoneId: ZoneId = UTCZoneId

  def msgEventReceiver: Receive = {
    case mc: MsgCreated =>
      val receivedOrders = mc.thread.map(th => th.receivedOrders.map(ro => ro.from -> ro.order).toMap)
      val msgThread = mc.thread.map(th => MsgThread(Evt.getOptionFromValue(th.id),
        Evt.getOptionFromValue(th.parentId), receivedOrders, Option(th.senderOrder)))
      val msg = Msg(mc.typ, mc.senderDID, mc.statusCode,
        getZonedDateTimeFromMillis(mc.creationTimeInMillis),
        getZonedDateTimeFromMillis(mc.lastUpdatedTimeInMillis),
        Evt.getOptionFromValue(mc.refMsgId), msgThread, mc.sendMsg)
      msgs += mc.uid -> msg
      updateMsgIndexes(mc.uid, msg)
      updateMsgDeliveryState(mc.uid)

    case ma: MsgAnswered =>
      msgs.get(ma.uid).foreach { msg =>
        val updated = msg.copy(statusCode = ma.statusCode, refMsgId = Option(ma.refMsgId),
          lastUpdatedDateTime = getZonedDateTimeFromMillis(ma.lastUpdatedTimeInMillis))
        msgs += ma.uid -> updated
        updateMsgIndexes(ma.uid, updated)
      }

    case msu: MsgStatusUpdated =>
      msgs.get(msu.uid).foreach { msg =>
        val updated = msg.copy(statusCode = msu.statusCode, lastUpdatedDateTime =
          getZonedDateTimeFromMillis(msu.lastUpdatedTimeInMillis))
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
        msgDetails -= mdsu.uid
      } else {
        val emds = msgDeliveryStatus.getOrElse(mdsu.uid, Map.empty)
        val newMds = MsgDeliveryStatus(mdsu.statusCode, Evt.getOptionFromValue(mdsu.statusDetail),
          getZonedDateTimeFromMillis(mdsu.lastUpdatedTimeInMillis), mdsu.failedAttemptCount)
        val nmds = emds ++ Map(mdsu.to -> newMds)
        msgDeliveryStatus += mdsu.uid -> nmds
      }
      updateMsgDeliveryState(mdsu.uid, Option(mdsu))

    case mda: MsgDetailAdded =>
      val emd = msgDetails.getOrElse(mda.uid, Map.empty)
      val nmd = emd ++ Map(mda.name -> mda.value)
      msgDetails += mda.uid -> nmd

    case meps: MsgPayloadStored =>
      val metadata = meps.payloadContext.map { pc => PayloadMetadata(pc.msgType, pc.msgPackVersion) }
      msgPayloads += meps.uid -> PayloadWrapper(meps.payload.toByteArray, metadata)

    case me: MsgExpirationTimeUpdated =>
      msgExpirationTime = msgExpirationTime ++ Map(me.msgType -> me.timeInSeconds)
  }

  def updateMsgDeliveryState(msgId: MsgId, mdsu: Option[MsgDeliveryStatusUpdated]=None): Unit = {
    getMsgOpt(msgId).foreach { msg =>
      val deliveryStatus = getMsgDeliveryStatus(msgId)
      msgDeliveryState.foreach(_.updateDeliveryState(msgId, msg, deliveryStatus, mdsu))
    }
  }

  def buildMsgCreatedEvt(mType: String, senderDID: DID, msgId: MsgId, sendMsg: Boolean,
                         msgStatus: String, threadOpt: Option[MsgThread], LEGACY_refMsgId: Option[MsgId]=None): MsgCreated = {
    checkIfMsgAlreadyNotExists(msgId)
    checkIfDIDLengthIsValid(senderDID)
    val msgReceivedOrderDetail = threadOpt.flatMap(_.getReceivedOrders).getOrElse(Map.empty).
      map(ro => MsgReceivedOrdersDetail(ro._1, ro._2)).toSeq
    val msgThreadDetail = threadOpt.map(th => MsgThreadDetail(
      Evt.getStringValueFromOption(th.thid), Evt.getStringValueFromOption(th.pthid), th.getSenderOrder, msgReceivedOrderDetail))
    MsgCreated(msgId, mType, senderDID, msgStatus,
      getMillisForCurrentUTCZonedDateTime, getMillisForCurrentUTCZonedDateTime,
      LEGACY_refMsgId.getOrElse(Evt.defaultUnknownValueForStringType), msgThreadDetail, sendMsg)
  }

  val seenMsgStatusCode: Set[String] = Set(
    MSG_STATUS_CREATED, MSG_STATUS_SENT,
    MSG_STATUS_ACCEPTED, MSG_STATUS_REJECTED,
    MSG_STATUS_REVIEWED).map(_.statusCode)

  def updateMsgIndexes(msgId: MsgId, msg: Msg): Unit = {
    if (msg.statusCode == MSG_STATUS_RECEIVED.statusCode) {
      unseenMsgIds += msgId
    } else if (seenMsgStatusCode.contains(msg.statusCode)) {
      seenMsgIds += msgId
      unseenMsgIds -= msgId
    }
    msg.refMsgId.foreach { refMsgId =>
      refMsgIdToMsgId += refMsgId -> msgId
    }
  }

  /**
   * THIS IS NOT USED AS OF NOW (but will be once we thoroughly test this logic)
   *
   * This is initial implementation of in-memory msg state cleanup for stale messages
   * once we know this change is working at high level, then we should finalize it,
   * make it more robust if needed (at least for short term)
   *
   */
  def performStateCleanup(maxTimeToRetainSeenMsgsInMinutes: Int): Unit = {
    val currentDateTime = getCurrentUTCZonedDateTime
    val candidateMsgIds = seenMsgIds.filter { msgId =>
      val msg = msgs(msgId)
      val duration = ChronoUnit.MINUTES.between(msg.creationDateTime, currentDateTime)
      duration >= maxTimeToRetainSeenMsgsInMinutes
    }
    msgs --= candidateMsgIds
    msgDetails --= candidateMsgIds
    msgPayloads --= candidateMsgIds
    msgDeliveryStatus --= candidateMsgIds
    seenMsgIds --= candidateMsgIds
    unseenMsgIds --= candidateMsgIds
    refMsgIdToMsgId = refMsgIdToMsgId.filterNot { case (refMsgId, msgId) =>
      candidateMsgIds.contains(refMsgId) || candidateMsgIds.contains(msgId)
    }
  }
}

