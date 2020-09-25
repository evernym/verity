package com.evernym.verity.actor.agent.user

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.{Evt, MsgAnswered}
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgFromDriver}
import com.evernym.verity.agentmsg.msgfamily.pairwise.MsgThread
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
      val thread = Option(MsgThread(Option(threadContext.threadId)))
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
                      threadOpt: Option[MsgThread],
                      refMsgId: Option[MsgId],
                      payloadParam: Option[StorePayloadParam],
                      useAsyncPersist: Boolean): MsgStored = {
    val msgCreatedEvent = state.msgState.buildMsgCreatedEvt(msgName, senderDID,
      msgId, sendMsg = sendMsg, statusCode, threadOpt, refMsgId)
    storeMsg(msgCreatedEvent, payloadParam, useAsyncPersist)
  }
}
