package com.evernym.verity.actor.agent.user

import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.ALREADY_EXISTS
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.actor.{Evt, MsgAnswered, MsgCreated}
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgFromDriver}
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.protocol.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine.{DID, MsgId}
import com.evernym.verity.protocol.protocols.StorePayloadParam
import com.evernym.verity.protocol.protocols.connecting.common.{AddMsg, UpdateDeliveryStatus, UpdateMsg}

import scala.concurrent.Future

/**
 * TODO: to be deprecated after connecting 0.5 and 0.6 protocol gets deprecated
 */
trait LEGACY_connectingSignalHandler { this: UserAgentCommon =>

  def handleLegacySignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = {
    case SignalMsgFromDriver(am: AddMsg, _, _, pinstId) =>
      val threadContext = getThreadContext(pinstId)
      val thread = Option(Thread(Option(threadContext.threadId)))
      LEGACY_storeMsg(
        am.msgId,
        am.msgType,
        am.senderDID,
        am.statusCode,
        sendMsg = am.sendMsg,
        thread,
        am.refMsgId,
        am.payload.map(StorePayloadParam(_, None)),
        useAsyncPersist = false)
      Future(None)

    case SignalMsgFromDriver(um: UpdateMsg, _, _, _) =>
      handleMsgAnswered(MsgAnswered(um.msgId, um.statusCode, Evt.getStringValueFromOption(um.refMsgId),
        getMillisForCurrentUTCZonedDateTime))
      Future(None)

    case SignalMsgFromDriver(umds: UpdateDeliveryStatus, _, _, _) =>
      updateMsgDeliveryStatus(UpdateMsgDeliveryStatus(umds.msgId, umds.to, umds.newStatusCode, umds.statusDetail))
      Future(None)
  }

  def LEGACY_storeMsg(msgId: MsgId,
                      msgName: String,
                      senderDID: DID,
                      statusCode: String,
                      sendMsg: Boolean,
                      threadOpt: Option[Thread],
                      refMsgId: Option[MsgId],
                      payloadParam: Option[StorePayloadParam],
                      useAsyncPersist: Boolean): MsgStored = {
    val msgCreatedEvent = buildMsgCreatedEvt(msgName, senderDID,
      msgId, sendMsg = sendMsg, statusCode, threadOpt, refMsgId)
    storeMsg(msgCreatedEvent, payloadParam, useAsyncPersist)
  }

  def buildMsgCreatedEvt(mType: String, senderDID: DID, msgId: MsgId, sendMsg: Boolean,
                         msgStatus: String, threadOpt: Option[Thread], LEGACY_refMsgId: Option[MsgId]=None): MsgCreated = {
    checkIfMsgAlreadyNotExists(msgId)
    MsgHelper.buildMsgCreatedEvt(mType, senderDID, msgId, sendMsg,
      msgStatus, threadOpt, LEGACY_refMsgId)
  }

  def checkIfMsgAlreadyNotExists(msgId: MsgId): Unit = {
    if (getMsgOpt(msgId).isDefined) {
      throw new BadRequestErrorException(ALREADY_EXISTS.statusCode, Option("msg with uid already exists: " + msgId))
    }
  }
}
