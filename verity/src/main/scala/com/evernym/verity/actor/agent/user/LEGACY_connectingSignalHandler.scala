package com.evernym.verity.actor.agent.user

import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.actor.{Evt, MsgAnswered}
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgParam}
import com.evernym.verity.did.DidStr
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.protocol.container.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine.MsgId
import com.evernym.verity.protocol.protocols.connecting.common.{AddMsg, UpdateDeliveryStatus, UpdateMsg}

import scala.concurrent.Future

/**
 * TODO: to be deprecated after connecting 0.5 and 0.6 protocol gets deprecated
 */
trait LEGACY_connectingSignalHandler { this: UserAgentCommon =>

  def handleLegacySignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = {
    case SignalMsgParam(am: AddMsg, threadId) =>
      val thread = Option(Thread(threadId))
      LEGACY_storeMsg(
        am.msgId,
        am.msgType,
        am.senderDID,
        am.statusCode,
        am.sendMsg,
        thread,
        am.refMsgId,
        am.payload.map(StorePayloadParam(_, None)),
        useAsyncPersist = false)
      Future(None)

    case SignalMsgParam(um: UpdateMsg, _) =>
      handleMsgAnswered(MsgAnswered(um.msgId, um.statusCode, Evt.getStringValueFromOption(um.refMsgId),
        getMillisForCurrentUTCZonedDateTime))
      Future(None)

    case SignalMsgParam(umds: UpdateDeliveryStatus, _) =>
      updateMsgDeliveryStatus(UpdateMsgDeliveryStatus(umds.msgId, umds.to, umds.newStatusCode, umds.statusDetail))
      Future(None)
  }

  def LEGACY_storeMsg(msgId: MsgId,
                      msgName: String,
                      senderDID: DidStr,
                      statusCode: String,
                      sendMsg: Boolean,
                      threadOpt: Option[Thread],
                      refMsgId: Option[MsgId],
                      payloadParam: Option[StorePayloadParam],
                      useAsyncPersist: Boolean): MsgStoredEvents = {
    storeMsg(msgId, msgName, senderDID, statusCode, sendMsg, threadOpt, refMsgId, payloadParam, useAsyncPersist)
  }

}
