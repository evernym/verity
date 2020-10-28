package com.evernym.verity.actor.agent.user

import java.time.temporal.ChronoUnit

import akka.actor.Actor.Receive
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.Status.{DATA_NOT_FOUND, INVALID_VALUE, MSG_DELIVERY_STATUS_SENT, MSG_STATUS_ACCEPTED, MSG_STATUS_CREATED, MSG_STATUS_RECEIVED, MSG_STATUS_REDIRECTED, MSG_STATUS_REJECTED, MSG_STATUS_REVIEWED, MSG_STATUS_SENT, MSG_VALIDATION_ERROR_ALREADY_ANSWERED}
import com.evernym.verity.actor.{Evt, MsgAnswered, MsgCreated, MsgDeliveryStatusUpdated, MsgDetailAdded, MsgExpirationTimeUpdated, MsgPayloadStored, MsgReceivedOrdersDetail, MsgStatusUpdated, MsgThreadDetail}
import com.evernym.verity.actor.agent.{Msg, MsgDeliveryDetail, PayloadMetadata, PayloadWrapper, Thread}
import com.evernym.verity.agentmsg.msgfamily.pairwise.UpdateMsgStatusReqMsg
import com.evernym.verity.protocol.engine.{DID, MsgId, MsgName, RefMsgId}
import com.evernym.verity.protocol.protocols.MsgDeliveryState
import com.evernym.verity.util.TimeZoneUtil.{getCurrentUTCZonedDateTime, getMillisForCurrentUTCZonedDateTime}
import MsgHelper._
import com.evernym.verity.util.Util.checkIfDIDLengthIsValid

trait MsgAndDeliveryHandler {

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

  protected var unseenMsgIds: Set[MsgId] = Set.empty
  private var seenMsgIds: Set[MsgId] = Set.empty

  /**
   * mapping between MsgName (message type name, like: connection req, cred etc)
   * and it's expiration time in seconds
   */
  type Seconds = Int
  private var msgExpirationTime: Map[MsgName, Seconds] = Map.empty

  def msgEventReceiver: Receive = {
    case mc: MsgCreated =>
      val receivedOrders = mc.thread.map(th => th.receivedOrders.map(ro => ro.from -> ro.order).toMap).getOrElse(Map.empty)
      val msgThread = mc.thread.map(th => Thread(
        Evt.getOptionFromValue(th.id),
        Evt.getOptionFromValue(th.parentId),
        Option(th.senderOrder),
        receivedOrders))
      val msg = Msg(mc.typ, mc.senderDID, mc.statusCode,
        mc.creationTimeInMillis,
        mc.lastUpdatedTimeInMillis,
        Evt.getOptionFromValue(mc.refMsgId), msgThread, mc.sendMsg)
      addToMsgs(mc.uid, msg)
      updateMsgIndexes(mc.uid, msg)
      updateMsgDeliveryState(mc.uid)

    case ma: MsgAnswered =>
      getMsgOpt(ma.uid).foreach { msg =>
        val updated = msg.copy(statusCode = ma.statusCode, refMsgId = Option(ma.refMsgId),
          lastUpdatedTimeInMillis = ma.lastUpdatedTimeInMillis)
        addToMsgs(ma.uid, updated)
        updateMsgIndexes(ma.uid, updated)
      }

    case msu: MsgStatusUpdated =>
      getMsgOpt(msu.uid).foreach { msg =>
        val updated = msg.copy(statusCode = msu.statusCode, lastUpdatedTimeInMillis =
          msu.lastUpdatedTimeInMillis)
        addToMsgs(msu.uid, updated)
        updateMsgIndexes(msu.uid, updated)
      }

    case mdsu: MsgDeliveryStatusUpdated =>
      if (mdsu.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode) {
        getMsgOpt(mdsu.uid).filter(_.statusCode == MSG_STATUS_CREATED.statusCode).foreach { msg =>
          val updated = msg.copy(statusCode = MSG_STATUS_SENT.statusCode)
          addToMsgs(mdsu.uid, updated)
        }
        removeFromMsgDeliveryStatus(mdsu.uid)
        //msgDetails -= mdsu.uid
      } else {
        val emds = getMsgDeliveryStatus(mdsu.uid)
        val newMds = MsgDeliveryDetail(mdsu.statusCode, Evt.getOptionFromValue(mdsu.statusDetail),
          mdsu.failedAttemptCount, mdsu.lastUpdatedTimeInMillis)
        val nmds = emds ++ Map(mdsu.to -> newMds)
        addToDeliveryStatus(mdsu.uid, nmds)
      }
      updateMsgDeliveryState(mdsu.uid, Option(mdsu))

    case mda: MsgDetailAdded =>
      val emd = getMsgDetails(mda.uid)
      val nmd = emd ++ Map(mda.name -> mda.value)
      addToMsgDetails(mda.uid, nmd)

    case meps: MsgPayloadStored =>
      val metadata = meps.payloadContext.map { pc => PayloadMetadata(pc.msgType, pc.msgPackVersion) }
      addToMsgPayloads(meps.uid, PayloadWrapper(meps.payload.toByteArray, metadata))

    case me: MsgExpirationTimeUpdated =>
      msgExpirationTime = msgExpirationTime ++ Map(me.msgType -> me.timeInSeconds)
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

  def getReplyToMsgId(msgId: MsgId): Option[MsgId] = {
    refMsgIdToMsgId.get(msgId)
  }

  def addToMsgs(msgId: MsgId, msg: Msg): Unit
  def getMsgOpt(msgId: MsgId): Option[Msg]
  def getMsgReq(uid: MsgId): Msg = getMsgOpt(uid).getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"msg not found with uid: " + uid)))

  def removeFromMsgs(msgIds: Set[MsgId]): Unit

  def getMsgDetails(msgId: MsgId): Map[String, String]
  def addToMsgDetails(msgId: MsgId, details: Map[String, String]): Unit
  def removeFromMsgDetails(msgIds: Set[MsgId]): Unit

  def addToMsgPayloads(msgId: MsgId, payloadWrapper: PayloadWrapper): Unit
  def getMsgPayload(uid: MsgId): Option[PayloadWrapper]
  def getMsgPayloadReq(uid: MsgId): PayloadWrapper = getMsgPayload(uid).getOrElse(
    throw new InternalServerErrorException(DATA_NOT_FOUND.statusCode, Option("payload not found"))
  )

  def removeFromMsgPayloads(msgIds: Set[MsgId]): Unit

  def msgDeliveryState: Option[MsgDeliveryState]
  def getMsgDeliveryStatus(msgId: MsgId): Map[String, MsgDeliveryDetail]
  def addToDeliveryStatus(msgId: MsgId, deliveryDetails: Map[String, MsgDeliveryDetail]): Unit
  def removeFromMsgDeliveryStatus(msgIds: Set[MsgId]): Unit
  def removeFromMsgDeliveryStatus(msgId: MsgId): Unit = removeFromMsgDeliveryStatus(Set(msgId))

  def updateMsgDeliveryState(msgId: MsgId, mdsu: Option[MsgDeliveryStatusUpdated]=None): Unit = {
    getMsgOpt(msgId).foreach { msg =>
      val deliveryStatus = getMsgDeliveryStatus(msgId)
      msgDeliveryState.foreach(_.updateDeliveryState(msgId, msg, deliveryStatus, mdsu))
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
      getMsgOpt(msgId).exists { m =>
        val duration = ChronoUnit.MINUTES.between(m.creationDateTime, currentDateTime)
        duration >= maxTimeToRetainSeenMsgsInMinutes
      }
    }
    removeFromMsgs(candidateMsgIds)
    removeFromMsgDetails(candidateMsgIds)
    removeFromMsgPayloads(candidateMsgIds)
    removeFromMsgDeliveryStatus(candidateMsgIds)

    seenMsgIds --= candidateMsgIds
    unseenMsgIds --= candidateMsgIds
    refMsgIdToMsgId = refMsgIdToMsgId.filterNot { case (refMsgId, msgId) =>
      candidateMsgIds.contains(refMsgId) || candidateMsgIds.contains(msgId)
    }
  }

  def checkIfMsgExists(uidOpt: Option[MsgId]): Unit = {
    uidOpt.foreach { uid =>
      if (getMsgOpt(uid).isEmpty) {
        throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"msg not found with uid: " + uid))
      }
    }
  }

  def checkIfMsgAlreadyNotInAnsweredState(msgId: MsgId): Unit = {
    if (getMsgOpt(msgId).exists(m => validAnsweredMsgStatuses.contains(m.statusCode))){
      throw new BadRequestErrorException(MSG_VALIDATION_ERROR_ALREADY_ANSWERED.statusCode,
        Option("msg is already answered (uid: " + msgId + ")"))
    }
  }

  def validateNonConnectingMsgs(uid: MsgId, msg: Msg, ums: UpdateMsgStatusReqMsg): Unit = {
    //if msg is already answered, lets not update it and throw appropriate error
    if (validAnsweredMsgStatuses.contains(ums.statusCode)) {
      checkIfMsgAlreadyNotInAnsweredState(uid)
    }
    //is new status is in our allowed new status list, if not, throw appropriate error
    if (! validNewMsgStatusesAllowedToBeUpdatedTo.contains(ums.statusCode)) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("invalid update status code: " + ums.statusCode + s" (uid: $uid)"))
    }
    if (! validExistingMsgStatusesAllowedToBeUpdated.contains(msg.statusCode)) {
      throw new BadRequestErrorException(INVALID_VALUE.statusCode,
        Option("msg is not in a state where it can be updated with the given status code: " + ums.statusCode + s" (uid: $uid)"))
    }
  }

  def checkIfMsgStatusCanBeUpdatedToNewStatus(ums: UpdateMsgStatusReqMsg): Unit = {
    val uids = ums.uids.map(_.trim).toSet
    uids.foreach { uid =>
      getMsgOpt(uid) match {
        case Some(msg) => validateNonConnectingMsgs(uid, msg, ums)
        case None => throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("not allowed"))
      }
    }
  }
}

object MsgHelper {

  val seenMsgStatusCodes: Set[String] = Set(
    MSG_STATUS_CREATED, MSG_STATUS_SENT,
    MSG_STATUS_ACCEPTED, MSG_STATUS_REJECTED,
    MSG_STATUS_REVIEWED).map(_.statusCode)

  val validAnsweredMsgStatuses: Set[String] = Set(
    MSG_STATUS_ACCEPTED,
    MSG_STATUS_REJECTED,
    MSG_STATUS_REDIRECTED
  ).map(_.statusCode)

  val validNewMsgStatusesAllowedToBeUpdatedTo: Set[String] =
    validAnsweredMsgStatuses ++ Set(MSG_STATUS_REVIEWED.statusCode)

  val validExistingMsgStatusesAllowedToBeUpdated: Set[String] =
    Set(MSG_STATUS_RECEIVED, MSG_STATUS_ACCEPTED, MSG_STATUS_REJECTED).map(_.statusCode)

  def buildMsgCreatedEvt(mType: String, senderDID: DID, msgId: MsgId, sendMsg: Boolean,
                         msgStatus: String, threadOpt: Option[Thread], LEGACY_refMsgId: Option[MsgId]=None): MsgCreated = {
    checkIfDIDLengthIsValid(senderDID)
    val msgReceivedOrderDetail: Seq[MsgReceivedOrdersDetail] =
      threadOpt.map(_.received_orders).getOrElse(Map.empty)
        .map(ro => MsgReceivedOrdersDetail(ro._1, ro._2))
        .toSeq
    val msgThreadDetail = threadOpt.map(th => MsgThreadDetail(
      Evt.getStringValueFromOption(th.thid), Evt.getStringValueFromOption(th.pthid), th.senderOrderReq, msgReceivedOrderDetail))
    MsgCreated(msgId, mType, senderDID, msgStatus,
      getMillisForCurrentUTCZonedDateTime, getMillisForCurrentUTCZonedDateTime,
      LEGACY_refMsgId.getOrElse(Evt.defaultUnknownValueForStringType), msgThreadDetail, sendMsg)
  }
}