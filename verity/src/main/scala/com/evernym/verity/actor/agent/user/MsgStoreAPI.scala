package com.evernym.verity.actor.agent.user

import akka.actor.ActorRef
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.MsgPayloadStoredEventBuilder
import com.evernym.verity.actor.{Evt, MsgAnswered, MsgCreated, MsgDeliveryStatusUpdated, MsgPayloadStored, MsgStatusUpdated}
import com.evernym.verity.Status.{ALREADY_EXISTS, MSG_DELIVERY_STATUS_FAILED, MSG_STATUS_CREATED, MSG_STATUS_RECEIVED}
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil.{MSG_TYPE_GET_MSGS, MSG_TYPE_UPDATE_MSG_STATUS}
import com.evernym.verity.agentmsg.msgfamily.pairwise.{GetMsgsMsgHelper, UpdateMsgStatusMsgHelper, UpdateMsgStatusReqMsg}
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgWrapper}
import com.evernym.verity.constants.Constants.RESOURCE_TYPE_MESSAGE
import com.evernym.verity.protocol.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine.{DID, MsgId}
import com.evernym.verity.protocol.protocols.{MsgDetail, StorePayloadParam}
import com.evernym.verity.util.ReqMsgContext
import com.evernym.verity.util.TimeZoneUtil._
import com.evernym.verity.vault.{EncryptParam, KeyParam}

import scala.util.Left

/**
 * apis which creates/updates messages and/or it's delivery status
 */

trait MsgStoreAPI { this: UserAgentCommon =>

  /**
   * Get messages API
   * @param amw agent msg wrapper
   * @param reqMsgContext req msg context
   */
  def handleGetMsgs(amw: AgentMsgWrapper)(implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("handleGetMsgs", "UserAgentCommon") {
      addUserResourceUsage(reqMsgContext.clientIpAddressReq, RESOURCE_TYPE_MESSAGE, MSG_TYPE_GET_MSGS, ownerDID)
      val gmr = GetMsgsMsgHelper.buildReqMsg(amw)
      logger.debug("get msgs request: " + gmr)
      val allMsgs = getMsgs(gmr)
      buildAndSendGetMsgsResp(allMsgs, sender())
    }
  }

  private def buildAndSendGetMsgsResp(filteredMsgs: List[MsgDetail], sndr: ActorRef)
                             (implicit reqMsgContext: ReqMsgContext): Unit = {
    runWithInternalSpan("buildAndSendGetMsgsResp", "UserAgentCommon") {
      val getMsgsRespMsg = GetMsgsMsgHelper.buildRespMsg(filteredMsgs)(reqMsgContext.agentMsgContext)

      val encParam = EncryptParam(
        Set(KeyParam(Left(reqMsgContext.originalMsgSenderVerKeyReq))),
        Option(KeyParam(Left(state.thisAgentVerKeyReq)))
      )
      val logPrefix = "\n  => "
      logger.debug(s"filtered get msgs: $logPrefix" + filteredMsgs.mkString(logPrefix))
      logger.debug("get msgs response: " + getMsgsRespMsg)
      val param = AgentMsgPackagingUtil.buildPackMsgParam(encParam, getMsgsRespMsg, reqMsgContext.msgPackFormat == MPF_MSG_PACK)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormat, param)(agentMsgTransformer, wap)
      sendRespMsg("GetMsgResp", rp, sndr)
    }
  }

  /**
   * UpdateMsgStatus API (used to update delivered message's status)
   * @param updateMsgStatus update msg status request msg
   * @param reqMsgContext req msg context
   */
  def handleUpdateMsgStatus(updateMsgStatus: UpdateMsgStatusReqMsg)
                           (implicit reqMsgContext: ReqMsgContext): Unit = {
    addUserResourceUsage(reqMsgContext.clientIpAddressReq, RESOURCE_TYPE_MESSAGE,
      MSG_TYPE_UPDATE_MSG_STATUS, Option(domainId))
    checkIfMsgStatusCanBeUpdatedToNewStatus(updateMsgStatus)

    val uids = updateMsgStatus.uids.map(_.trim).toSet
    val events = uids.map {  uid =>
      MsgStatusUpdated(uid, updateMsgStatus.statusCode, getMillisForCurrentUTCZonedDateTime)
    }
    writeAndApplyAll(events.toList)
    val msgStatusUpdatedRespMsg = UpdateMsgStatusMsgHelper.buildRespMsg(updateMsgStatus.uids,
      updateMsgStatus.statusCode)(reqMsgContext.agentMsgContext)
    val encParam = EncryptParam(
      Set(KeyParam(Left(reqMsgContext.originalMsgSenderVerKeyReq))),
      Option(KeyParam(Left(state.thisAgentVerKeyReq)))
    )
    val param = AgentMsgPackagingUtil.buildPackMsgParam(encParam, msgStatusUpdatedRespMsg)
    val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormat, param)(agentMsgTransformer, wap)
    sendRespMsg("MsgStatusUpdatedResp", rp)
  }

  def checkIfMsgAlreadyNotExists(msgId: MsgId): Unit = {
    if (getMsgOpt(msgId).isDefined) {
      throw new BadRequestErrorException(ALREADY_EXISTS.statusCode, Option("msg with uid already exists: " + msgId))
    }
  }

  def storeMsg(msgId: MsgId,
               msgName: String,
               senderDID: DID,
               msgStatusCode: String,
               sendMsg: Boolean,
               threadOpt: Option[Thread],
               refMsgId: Option[MsgId],
               payloadParam: Option[StorePayloadParam],
               useAsyncPersist: Boolean = false): MsgStoredEvents = {
    val msgStored = buildMsgStoredEventsV2(msgId, msgName, senderDID, msgStatusCode,
      sendMsg, threadOpt, refMsgId, payloadParam)
    if (useAsyncPersist)
      asyncWriteAndApplyAll(msgStored.allEvents)
    else
      writeAndApplyAll(msgStored.allEvents)
    msgStored
  }

  def buildMsgStoredEventsV1(msgId: MsgId,
                             msgName: String,
                             myPairwiseDID: DID,
                             senderDID: DID,
                             sendMsg: Boolean,
                             threadOpt: Option[Thread],
                             refMsgId: Option[MsgId],
                             payloadParam: Option[StorePayloadParam]): MsgStoredEvents = {
    val statusCode = if (senderDID == myPairwiseDID) MSG_STATUS_CREATED.statusCode else MSG_STATUS_RECEIVED.statusCode
    buildMsgStoredEventsV2(msgId, msgName, senderDID, statusCode, sendMsg, threadOpt, refMsgId, payloadParam)
  }

  def buildMsgStoredEventsV2(msgId: MsgId,
                             msgName: String,
                             senderDID: DID,
                             statusCode: String,
                             sendMsg: Boolean,
                             threadOpt: Option[Thread],
                             refMsgId: Option[MsgId],
                             payloadParam: Option[StorePayloadParam]): MsgStoredEvents = {
    val msgCreatedEvent = buildMsgCreatedEvt(msgName, senderDID,
      msgId, sendMsg = sendMsg, statusCode, threadOpt, refMsgId)
    buildMsgStoredEvents(msgCreatedEvent, payloadParam)
  }

  /**
   * creates/stores message
   *
   * @param msgCreatedEvent msg created event
   * @param payloadParam payload param
   * @return
   */
  private def buildMsgStoredEvents(msgCreatedEvent: MsgCreated, payloadParam: Option[StorePayloadParam]):
  MsgStoredEvents = {
    val payloadStored = buildPayloadEvent(msgCreatedEvent.uid, payloadParam)
    MsgStoredEvents(msgCreatedEvent, payloadStored)
  }

  private def buildMsgCreatedEvt(mType: String, senderDID: DID, msgId: MsgId, sendMsg: Boolean,
                         msgStatus: String, threadOpt: Option[Thread],
                         LEGACY_refMsgId: Option[MsgId]=None): MsgCreated = {
    checkIfMsgAlreadyNotExists(msgId)
    MsgHelper.buildMsgCreatedEvt(mType, senderDID, msgId, sendMsg,
      msgStatus, threadOpt, LEGACY_refMsgId)
  }

  private def buildPayloadEvent(msgId: MsgId,
                                payloadParam: Option[StorePayloadParam]): Option[MsgPayloadStored] = {
    payloadParam.map { pp =>
      MsgPayloadStoredEventBuilder.buildMsgPayloadStoredEvt(msgId, pp.message, pp.metadata)
    }
  }

  /**
   * updates stored message attributes as per answer message (accepted, rejected etc)
   * (for example, it updates new status code, ref message id etc)
   *
   * NOTE: currently, this is only used by connecting 0.5 and 0.6 protocols
   *
   * @param msgAnswered msg answered event
   */
  def handleMsgAnswered(msgAnswered: MsgAnswered): Unit = {
    writeAndApply(msgAnswered)
  }

  /**
   * Update Msg Delivery Status api
   * @param umds update msg delivery status
   */
  def updateMsgDeliveryStatus(umds: UpdateMsgDeliveryStatus): Unit = {
    val msgDeliveryStatuses = getMsgDeliveryStatus(umds.uid)
    val deliveryStatusByDestination = msgDeliveryStatuses.get(umds.to)
    val existingFailedAttemptCount = deliveryStatusByDestination.map(_.failedAttemptCount).getOrElse(0)
    val newFailedAttemptCount =
      if (umds.statusCode == MSG_DELIVERY_STATUS_FAILED.statusCode) existingFailedAttemptCount + 1
      else existingFailedAttemptCount
    writeAndApply(MsgDeliveryStatusUpdated(umds.uid, umds.to, umds.statusCode,
      umds.statusDetail.getOrElse(Evt.defaultUnknownValueForStringType), getMillisForCurrentUTCZonedDateTime, newFailedAttemptCount))
  }
}
