package com.evernym.verity.actor.agent.user

import java.time.ZonedDateTime

import akka.actor.Actor.Receive
import com.evernym.verity.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.Status._
import com.evernym.verity.actor.{Evt, MsgAnswered, MsgCreated, MsgDeliveryStatusUpdated, MsgDetailAdded, MsgExpirationTimeUpdated, MsgPayloadStored, MsgReceivedOrdersDetail, MsgStatusUpdated, MsgThreadDetail}
import com.evernym.verity.actor.agent.{AgentCommon, Msg, MsgDeliveryDetail, PayloadMetadata, PayloadWrapper, Thread}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_TYPE_CONN_REQ_ACCEPTED, _}
import com.evernym.verity.agentmsg.msgfamily.pairwise.UpdateMsgStatusReqMsg
import com.evernym.verity.protocol.engine.{DID, MsgId, MsgName, RefMsgId}
import com.evernym.verity.protocol.protocols.MsgDeliveryState
import com.evernym.verity.util.TimeZoneUtil.getMillisForCurrentUTCZonedDateTime
import MsgHelper._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.Util.checkIfDIDLengthIsValid

trait MsgAndDeliveryHandler { this: AgentCommon =>

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

  def appConfig: AppConfig

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
        removeMsgIfReceivedByDestination(msu.uid)
      }

    case mdsu: MsgDeliveryStatusUpdated =>
      if (mdsu.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode) {
        getMsgOpt(mdsu.uid).filter(_.statusCode == MSG_STATUS_CREATED.statusCode).foreach { msg =>
          val updated = msg.copy(statusCode = MSG_STATUS_SENT.statusCode)
          addToMsgs(mdsu.uid, updated)
        }
        removeFromMsgDeliveryStatus(mdsu.uid)
      } else {
        val emds = getMsgDeliveryStatus(mdsu.uid)
        val newMds = MsgDeliveryDetail(mdsu.statusCode, Evt.getOptionFromValue(mdsu.statusDetail),
          mdsu.failedAttemptCount, mdsu.lastUpdatedTimeInMillis)
        val nmds = emds ++ Map(mdsu.to -> newMds)
        addToDeliveryStatus(mdsu.uid, nmds)
      }
      updateMsgDeliveryState(mdsu.uid, Option(mdsu))
      removeMsgIfReceivedByDestination(mdsu.uid)

    case mda: MsgDetailAdded =>
      val emd = getMsgDetails(mda.uid)
      val nmd = emd ++ Map(mda.name -> mda.value)
      addToMsgDetails(mda.uid, nmd)

    case meps: MsgPayloadStored =>
      val metadata = meps.payloadContext.map { pc => PayloadMetadata(pc.msgType, pc.msgPackFormat) }
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
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"msg not found with uid: $uid")))

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

  def checkIfMsgExists(uidOpt: Option[MsgId]): Unit = {
    uidOpt.foreach { uid =>
      if (getMsgOpt(uid).isEmpty) {
        throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"msg not found with uid: $uid"))
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

  def removeMsgIfReceivedByDestination(msgId: MsgId): Unit = {
    if (isMsgCandidateForRemoval(msgId)) removedMsgsFromState(Set(msgId))
  }

  /**
   * this state cleanup logic is to remove delivered messages
   * from the state object (which should help in memory consumption and
   * actor recovery time once we enable snapshotting)
   * this code is only needed until we do "outbox integration" work
   * which would have its own logic to determine which messages to keep into the state and not etc.
   * @param msgId
   * @return
   */
  def isMsgCandidateForRemoval(msgId: MsgId): Boolean = {
    getMsgOpt(msgId).forall { msg =>
      val msgDelivery = getMsgDeliveryStatus(msgId)
      val isMsgSuccessfullyDelivered = msgDelivery.exists(_._2.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode)
      val isMsgDeliveryAcknowledged = msg.statusCode == MSG_STATUS_REVIEWED.statusCode
      val isSentByMe = state.myDid.contains(msg.senderDID)

      val connReqMsgsOnInviteeSide = List(
        CREATE_MSG_TYPE_CONN_REQ,             //0.5
        MSG_TYPE_CONN_REQ                     //0.6
      )

      val connReqAnswerMsgsOnInviteeSide = List(
        CREATE_MSG_TYPE_CONN_REQ_ANSWER,      //0.5
        CREATE_MSG_TYPE_REDIRECT_CONN_REQ,    //0.5

        MSG_TYPE_ACCEPT_CONN_REQ,             //0.6
        MSG_TYPE_DECLINE_CONN_REQ,            //0.6
        MSG_TYPE_REDIRECT_CONN_REQ            //0.6
      )

      val connAnswerMsgsOnInviterSide = List(
        CREATE_MSG_TYPE_CONN_REQ_ANSWER,      //0.5
        CREATE_MSG_TYPE_REDIRECT_CONN_REQ,    //0.5
        CREATE_MSG_TYPE_CONN_REQ_REDIRECTED,  //0.5

        MSG_TYPE_CONN_REQ_ACCEPTED,           //0.6
        MSG_TYPE_CONN_REQ_REDIRECTED,         //0.6
      )

      val msgAnsweredStatusCodes = List(
        MSG_STATUS_ACCEPTED, MSG_STATUS_REJECTED, MSG_STATUS_REDIRECTED, MSG_STATUS_REVIEWED
      ).map(_.statusCode)

      val isConnReqMsgOnInviterSide =
        isSentByMe && connReqMsgsOnInviteeSide.contains(msg.getType) && msgAnsweredStatusCodes.contains(msg.statusCode)
      val isConnAnswerMsgOnInviteeSide =
        isSentByMe && connReqAnswerMsgsOnInviteeSide.contains(msg.getType) && msgAnsweredStatusCodes.contains(msg.statusCode)
      val isConnAnswerMsgOnInviterSide =
        ! isSentByMe && connAnswerMsgsOnInviterSide.contains(msg.getType) && msgAnsweredStatusCodes.contains(msg.statusCode)

      val isMsgOlderToBeRemoved = msg.lastUpdatedDateTime.isBefore(ZonedDateTime.now().minusDays(10))

      if (! isMsgAckNeeded && isMsgSuccessfullyDelivered) true                                      //VAS
      else if (isMsgAckNeeded && isConnReqMsgOnInviterSide && isMsgOlderToBeRemoved) true           //CAS & EAS
      else if (isMsgAckNeeded && isConnAnswerMsgOnInviteeSide && isMsgOlderToBeRemoved) true        //CAS & EAS
      else if (isMsgAckNeeded && isConnAnswerMsgOnInviterSide && isMsgOlderToBeRemoved) true        //CAS & EAS
      else if (isMsgAckNeeded && isMsgDeliveryAcknowledged) true                                    //CAS & EAS
      else false                                                                                    //VAS/CAS/EAS
    }
  }

  def removedMsgsFromState(msgIds: Set[MsgId]): Unit = {
    if (isStateMessagesCleanupEnabled) {
      removeFromMsgs(msgIds)
      removeFromMsgDetails(msgIds)
      removeFromMsgPayloads(msgIds)
      removeFromMsgDeliveryStatus(msgIds)
      removeMsgsFromLocalIndexState(msgIds)
    }
  }

  def removeMsgsFromLocalIndexState(msgIds: Set[MsgId]): Unit = {
    seenMsgIds --= msgIds
    unseenMsgIds --= msgIds
    refMsgIdToMsgId = refMsgIdToMsgId.filterNot { case (refMsgId, msgId) =>
      msgIds.contains(refMsgId) || msgIds.contains(msgId)
    }
  }

  /**
   * if this is VAS, then it doesn't acknowledges message receipt
   * and hence it would be ok to just consider message delivery status to
   * know if that message can be considered as delivered and acknowledged too
   */
  lazy val isMsgAckNeeded: Boolean =
    ! appConfig
      .getConfigStringOption(AKKA_SHARDING_REGION_NAME_USER_AGENT)
      .contains("VerityAgent")

  lazy val isStateMessagesCleanupEnabled: Boolean =
    appConfig.getConfigBooleanOption(AGENT_STATE_MESSAGES_CLEANUP_ENABLED).getOrElse(false)
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