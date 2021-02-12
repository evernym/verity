package com.evernym.verity.actor.agent.user

import akka.actor.Actor.Receive
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor.{Evt, MsgAnswered, MsgCreated, MsgDeliveryStatusUpdated, MsgDetailAdded, MsgExpirationTimeUpdated, MsgPayloadStored, MsgReceivedOrdersDetail, MsgStatusUpdated, MsgThreadDetail}
import com.evernym.verity.actor.agent.{AgentCommon, Msg, Thread}
import com.evernym.verity.agentmsg.msgfamily.pairwise.UpdateMsgStatusReqMsg
import com.evernym.verity.protocol.engine.{DID, MsgId}
import com.evernym.verity.util.TimeZoneUtil.getMillisForCurrentUTCZonedDateTime
import MsgHelper._
import com.evernym.verity.actor.agent.user.msgstore.MsgStore
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.Util.checkIfDIDLengthIsValid

trait MsgAndDeliveryHandler { this: AgentCommon =>

  def appConfig: AppConfig
  def msgStore: MsgStore

  def msgEventReceiver: Receive = {
    case mc: MsgCreated                   => msgStore.handleMsgCreated(mc)
    case mda: MsgDetailAdded              => msgStore.handleMsgDetailAdded(mda)
    case mps: MsgPayloadStored            => msgStore.handleMsgPayloadStored(mps)

    case ma: MsgAnswered                  => msgStore.handleMsgAnswered(ma)
    case msu: MsgStatusUpdated            => msgStore.handleMsgStatusUpdated(msu)

    case mdsu: MsgDeliveryStatusUpdated   => msgStore.handleMsgDeliveryStatusUpdated(mdsu)
    case metu: MsgExpirationTimeUpdated   => msgStore.handleMsgExpiryTimeUpdated(metu)
  }

  def checkIfMsgExists(uidOpt: Option[MsgId]): Unit = {
    uidOpt.foreach { uid =>
      if (msgStore.getMsgOpt(uid).isEmpty) {
        throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"msg not found with uid: $uid"))
      }
    }
  }

  def checkIfMsgStatusCanBeUpdatedToNewStatus(ums: UpdateMsgStatusReqMsg): Unit = {
    val uids = ums.uids.map(_.trim).toSet
    uids.foreach { uid =>
      msgStore.getMsgOpt(uid) match {
        case Some(msg) => validateNonConnectingMsgs(uid, msg, ums)
        case None => throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("not allowed"))
      }
    }
  }

  private def checkIfMsgAlreadyNotInAnsweredState(msgId: MsgId): Unit = {
    if (msgStore.getMsgOpt(msgId).exists(m => validAnsweredMsgStatuses.contains(m.statusCode))){
      throw new BadRequestErrorException(MSG_VALIDATION_ERROR_ALREADY_ANSWERED.statusCode,
        Option("msg is already answered (uid: " + msgId + ")"))
    }
  }

  private def validateNonConnectingMsgs(uid: MsgId, msg: Msg, ums: UpdateMsgStatusReqMsg): Unit = {
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

  def buildMsgCreatedEvt(msgId: MsgId,
                         mType: String,
                         senderDID: DID,
                         sendMsg: Boolean,
                         msgStatus: String,
                         threadOpt: Option[Thread],
                         LEGACY_refMsgId: Option[MsgId]=None): MsgCreated = {
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