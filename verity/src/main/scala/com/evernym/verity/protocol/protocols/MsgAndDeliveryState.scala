package com.evernym.verity.protocol.protocols

import java.time.ZonedDateTime

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.msghandler.outgoing.PayloadMetadata
import com.evernym.verity.agentmsg.msgfamily.pairwise.{MsgThread, UpdateMsgStatusReqMsg}
import com.evernym.verity.protocol.engine.{DID, MsgId, MsgPackVersion}


trait MsgAndDeliveryState {

  /**
   * this is only needed if the implementing class wants to use message retry feature
   * (in current use case, only UserAgentPairwise actor needs to override this)
   * @return
   */
  def msgDeliveryState: Option[MsgDeliveryState] = None

  val msgState: MsgState = new MsgState(msgDeliveryState)

  val validAnsweredMsgStatuses: Set[String] = Set(
    MSG_STATUS_ACCEPTED,
    MSG_STATUS_REJECTED,
    MSG_STATUS_REDIRECTED
  ).map(_.statusCode)

  val validNewMsgStatusesAllowedToBeUpdatedTo: Set[String] =
    validAnsweredMsgStatuses ++ Set(MSG_STATUS_REVIEWED.statusCode)

  val validExistingMsgStatusesAllowedToBeUpdated: Set[String] =
    Set(MSG_STATUS_RECEIVED, MSG_STATUS_ACCEPTED, MSG_STATUS_REJECTED).map(_.statusCode)

  def checkIfMsgAlreadyNotInAnsweredState(msgId: MsgId): Unit = {
    if (msgState.getMsgOpt(msgId).exists(m => validAnsweredMsgStatuses.contains(m.statusCode))){
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
      msgState.getMsgOpt(uid) match {
        case Some(msg) => validateNonConnectingMsgs(uid, msg, ums)
        case None => throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("not allowed"))
      }
    }
  }

}

//state
case class Msg(`type`: String, senderDID: DID, statusCode: String,
               creationDateTime: ZonedDateTime, lastUpdatedDateTime: ZonedDateTime,
               refMsgId: Option[String], thread: Option[MsgThread],
               sendMsg: Boolean) {
  def getType: String = `type`
}
case class MsgDeliveryStatus(statusCode: String, statusDetail: Option[String], lastUpdatedDateTime: ZonedDateTime, failedAttemptCount: Int)

//response msg

case class DeliveryStatus(to: String, statusCode: String, statusDetail: Option[String], lastUpdatedDateTime: String)

case class MsgDetail(uid: MsgId, `type`: String, senderDID: DID, statusCode: String,
                     refMsgId: Option[String], thread: Option[MsgThread],
                     payload: Option[Array[Byte]], deliveryDetails: Set[DeliveryStatus]) {

  override def toString: String = s"uid=$uid, type=${`type`}, senderDID=$senderDID, " +
    s"statusCode=$statusCode, thread=$thread, refMsgId=$refMsgId"
}

case class PayloadWrapper(msg: Array[Byte], metadata: Option[PayloadMetadata]) {
  def msgPackVersion: Option[MsgPackVersion] = metadata.map(md => md.msgPackVersion)
}

case class StorePayloadParam(message: Array[Byte], metadata: Option[PayloadMetadata])
