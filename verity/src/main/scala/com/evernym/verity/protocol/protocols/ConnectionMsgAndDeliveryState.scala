package com.evernym.verity.protocol.protocols

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor.agent.{Msg, PayloadMetadata}
import com.evernym.verity.agentmsg.msgfamily.pairwise.UpdateMsgStatusReqMsg
import com.evernym.verity.protocol.engine.{DID, MsgId}
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.actor.agent.user.MsgHelper._

//this is to store legacy connection related messages
trait ConnectionMsgAndDeliveryState {

  val connectingMsgState: ConnectionMsgState = new ConnectionMsgState

  def checkIfMsgAlreadyNotInAnsweredState(msgId: MsgId): Unit = {
    if (connectingMsgState.getMsgOpt(msgId).exists(m => validAnsweredMsgStatuses.contains(m.statusCode))){
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
      connectingMsgState.getMsgOpt(uid) match {
        case Some(msg) => validateNonConnectingMsgs(uid, msg, ums)
        case None => throw new BadRequestErrorException(INVALID_VALUE.statusCode, Option("not allowed"))
      }
    }
  }

}

case class DeliveryStatus(to: String, statusCode: String, statusDetail: Option[String], lastUpdatedDateTime: String)

case class MsgDetail(uid: MsgId, `type`: String, senderDID: DID, statusCode: String,
                     refMsgId: Option[String], thread: Option[Thread],
                     payload: Option[Array[Byte]], deliveryDetails: Set[DeliveryStatus]) {

  override def toString: String = s"uid=$uid, type=${`type`}, senderDID=$senderDID, " +
    s"statusCode=$statusCode, thread=$thread, refMsgId=$refMsgId"
}

case class StorePayloadParam(message: Array[Byte], metadata: Option[PayloadMetadata])
