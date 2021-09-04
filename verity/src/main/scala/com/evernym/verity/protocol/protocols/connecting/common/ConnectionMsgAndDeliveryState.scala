package com.evernym.verity.protocol.protocols.connecting.common

import com.evernym.verity.actor.agent.user.MsgHelper._
import com.evernym.verity.actor.agent.Msg
import com.evernym.verity.agentmsg.msgfamily.pairwise.UpdateMsgStatusReqMsg
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status._

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


