package com.evernym.verity.protocol.protocols

import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.msgsender.{MsgDeliveryResult, SendMsgParam}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{HasLogger, MsgId}
import com.evernym.verity.actor.wallet.PackedMsg


trait MsgDeliveryResultHandler extends HasAgentMsgTransformer { this: HasLogger =>

  def updateMsgDeliveryStatus(uid: MsgId, to: String, statusCode: String, statusMsg: Option[String]=None): Unit

  def msgSentSuccessfully(mss: MsgSentSuccessfully): Unit

  def msgSendingFailed(msf: MsgSendingFailed): Unit

  def updateLocalMsgDeliveryStatusAsFailed(uid: MsgId, to: String, statusMsg: Option[String]=None): Unit = {
    updateMsgDeliveryStatus(uid, to, MSG_DELIVERY_STATUS_FAILED.statusCode, statusMsg)
  }

  def updateLocalMsgDeliveryStatusAsSent(uid: MsgId, to: String, statusMsg: Option[String]=None): Unit = {
    updateMsgDeliveryStatus(uid, to, MSG_DELIVERY_STATUS_SENT.statusCode, statusMsg)
  }

  def handleSuccessfulMsgDelivery(sm: SendMsgParam, pm: PackedMsg): Unit = {
    logger.debug("handle successful msg delivery", (LOG_KEY_UID, sm.uid))
    updateMsgDeliveryStatus(sm.uid, sm.theirRoutingParam.routingTarget, MSG_DELIVERY_STATUS_SENT.statusCode)
    msgSentSuccessfully(MsgSentSuccessfully(sm.uid, sm.msgType))
  }

  def handleFailedMsgDelivery(sm: SendMsgParam, statusCode: String, statusMsg: Option[String]): Unit = {
    logger.info("handle failed msg delivery", (LOG_KEY_UID, sm.uid))
    if (! sm.isItARetryAttempt && statusCode == UNHANDLED.statusCode) {
      //if this is a first attempt and failure is unknown, record it as failed (it should be retried)
      logger.debug(s"first condition, isItARetryAttempt: ${sm.isItARetryAttempt} and statusCode: $statusCode")
      updateMsgDeliveryStatus(sm.uid, sm.theirRoutingParam.routingTarget, statusCode, statusMsg)
      msgSendingFailed(MsgSendingFailed(sm.uid, sm.msgType))
    } else if (sm.isItARetryAttempt && statusCode != UNHANDLED.statusCode) {
      //if this is a retry attempt and failure is known, then, msg delivery is successful, so record it too
      logger.debug(s"second condition, isItARetryAttempt: ${sm.isItARetryAttempt} and statusCode: $statusCode")
      updateMsgDeliveryStatus(sm.uid, sm.theirRoutingParam.routingTarget, MSG_DELIVERY_STATUS_SENT.statusCode, statusMsg)
    } else {
      //increment the failure count
      logger.debug(s"third condition, isItARetryAttempt: ${sm.isItARetryAttempt} and statusCode: $statusCode")
      updateMsgDeliveryStatus(sm.uid, sm.theirRoutingParam.routingTarget, MSG_DELIVERY_STATUS_FAILED.statusCode, statusMsg)
    }
  }

  def handleMsgDeliveryResult(mdr: MsgDeliveryResult): Unit = {
    logger.debug("handle msg delivery", (LOG_KEY_UID, mdr.sm.uid))
    mdr.responseMsg match {
      case Some(pm) => handleSuccessfulMsgDelivery(mdr.sm, pm)
      case None     => handleFailedMsgDelivery(mdr.sm, mdr.statusCode, mdr.statusMsg)
    }
  }
}

trait MsgDeliveryNotification extends Control with ActorMessage
case class MsgSentSuccessfully(uid: MsgId, typ: String) extends MsgDeliveryNotification
case class MsgSendingFailed(uid: MsgId, typ: String) extends MsgDeliveryNotification
