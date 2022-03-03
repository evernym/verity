package com.evernym.verity.actor.agent.user

import akka.actor.ActorRef
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.{ALREADY_EXISTS, MSG_DELIVERY_STATUS_FAILED, MSG_STATUS_CREATED, MSG_STATUS_RECEIVED}
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.PayloadMetadata
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.actor.resourceusagethrottling.RESOURCE_TYPE_MESSAGE
import com.evernym.verity.actor.resourceusagethrottling.helper.ResourceUsageUtil
import com.evernym.verity.agentmsg.msgfamily.pairwise.{GetMsgsMsgHelper, GetMsgsReqMsg, UpdateMsgStatusMsgHelper, UpdateMsgStatusReqMsg}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgPackagingUtil, AgentMsgWrapper}
import com.evernym.verity.did.DidStr
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.observability.metrics.InternalSpan
import com.evernym.verity.protocol.container.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.util.ReqMsgContext
import com.evernym.verity.util.TimeZoneUtil.getMillisForCurrentUTCZonedDateTime
import com.evernym.verity.util2.MsgPayloadStoredEventBuilder
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
    metricsWriter.runWithSpan("handleGetMsgs", "UserAgentCommon", InternalSpan) {
      val userId = userIdForResourceUsageTracking(amw.senderVerKey)
      val resourceName = ResourceUsageUtil.getMessageResourceName(amw.msgType)
      addUserResourceUsage(RESOURCE_TYPE_MESSAGE, resourceName, reqMsgContext.clientIpAddressReq, userId)
      val gmr = GetMsgsMsgHelper.buildReqMsg(amw)
      logger.debug("get msgs request: " + gmr)
      val allMsgs = msgStore.getMsgs(gmr)
      buildAndSendGetMsgsResp(allMsgs, sender())
    }
  }

  def handleGetMsgsInternal(gmr: GetMsgsReqMsg): Unit = {
    metricsWriter.runWithSpan("handleGetMsgsInternal", "UserAgentCommon", InternalSpan) {
      sender() ! GetMsgRespInternal(msgStore.getMsgs(gmr))
    }
  }

  private def buildAndSendGetMsgsResp(filteredMsgs: List[MsgDetail], sndr: ActorRef)
                             (implicit reqMsgContext: ReqMsgContext): Unit = {
    metricsWriter.runWithSpan("buildAndSendGetMsgsResp", "UserAgentCommon", InternalSpan) {
      val getMsgsRespMsg = GetMsgsMsgHelper.buildRespMsg(filteredMsgs)(reqMsgContext.agentMsgContext)

      val encParam = EncryptParam(
        Set(KeyParam(Left(reqMsgContext.originalMsgSenderVerKeyReq))),
        Option(KeyParam(Left(state.thisAgentVerKeyReq)))
      )
      val logPrefix = "\n  => "
      logger.debug(s"filtered get msgs: $logPrefix" + filteredMsgs.mkString(logPrefix))
      logger.debug("get msgs response: " + getMsgsRespMsg)
      val param = AgentMsgPackagingUtil.buildPackMsgParam(encParam, getMsgsRespMsg, reqMsgContext.wrapInBundledMsg)
      val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormatReq, param)(agentMsgTransformer, wap, metricsWriter)
      sendRespMsg("GetMsgsResp", rp, sndr)
    }
  }

  /**
   * UpdateMsgStatus API (used to update delivered message's status)
   * @param amw update msg status request msg
   * @param reqMsgContext req msg context
   */
  def handleUpdateMsgStatus(amw: AgentMsgWrapper)
                           (implicit reqMsgContext: ReqMsgContext): Unit = {
    val userId = userIdForResourceUsageTracking(amw.senderVerKey)
    val resourceName = ResourceUsageUtil.getMessageResourceName(amw.msgType)
    addUserResourceUsage(RESOURCE_TYPE_MESSAGE, resourceName, reqMsgContext.clientIpAddressReq, userId)
    val ums = UpdateMsgStatusMsgHelper.buildReqMsg(amw)
    val updatedMsgIds = handleUpdateMsgStatusBase(ums)
    val msgStatusUpdatedRespMsg = UpdateMsgStatusMsgHelper.buildRespMsg(updatedMsgIds,
      ums.statusCode)(reqMsgContext.agentMsgContext)
    val encParam = EncryptParam(
      Set(KeyParam(Left(reqMsgContext.originalMsgSenderVerKeyReq))),
      Option(KeyParam(Left(state.thisAgentVerKeyReq)))
    )
    val param = AgentMsgPackagingUtil.buildPackMsgParam(encParam, msgStatusUpdatedRespMsg, reqMsgContext.wrapInBundledMsg)
    val rp = AgentMsgPackagingUtil.buildAgentMsg(reqMsgContext.msgPackFormatReq, param)(agentMsgTransformer, wap, metricsWriter)
    sendRespMsg("MsgStatusUpdatedResp", rp)
  }

  def handleUpdateMsgStatusInternal(ums: UpdateMsgStatusReqMsg): Unit = {
    val updatedMsgIds = handleUpdateMsgStatusBase(ums)
    sender() ! UpdateMsgStatusRespInternal(updatedMsgIds)
  }

  private def handleUpdateMsgStatusBase(ums: UpdateMsgStatusReqMsg): List[MsgId] = {
    checkIfMsgStatusCanBeUpdatedToNewStatus(ums)
    val uids = ums.uids.map(_.trim).toSet.filter { msgId =>
      msgStore.getMsgOpt(msgId).isDefined
    }.toList

    val events = uids.map {  uid =>
      MsgStatusUpdated(uid, ums.statusCode, getMillisForCurrentUTCZonedDateTime)
    }
    writeAndApplyAll(events)
    uids
  }

  def checkIfMsgAlreadyNotExists(msgId: MsgId): Unit = {
    if (getMsgOpt(msgId).isDefined) {
      throw new BadRequestErrorException(ALREADY_EXISTS.statusCode, Option("msg with uid already exists: " + msgId))
    }
  }

  def storeMsg(msgId: MsgId,
               msgName: String,
               senderDID: DidStr,
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
                             myPairwiseDID: DidStr,
                             senderDID: DidStr,
                             sendMsg: Boolean,
                             threadOpt: Option[Thread],
                             refMsgId: Option[MsgId],
                             payloadParam: Option[StorePayloadParam]): MsgStoredEvents = {
    val statusCode = if (senderDID == myPairwiseDID) MSG_STATUS_CREATED.statusCode else MSG_STATUS_RECEIVED.statusCode
    buildMsgStoredEventsV2(msgId, msgName, senderDID, statusCode, sendMsg, threadOpt, refMsgId, payloadParam)
  }

  def buildMsgStoredEventsV2(msgId: MsgId,
                             msgName: String,
                             senderDID: DidStr,
                             statusCode: String,
                             sendMsg: Boolean,
                             threadOpt: Option[Thread],
                             refMsgId: Option[MsgId],
                             payloadParam: Option[StorePayloadParam]): MsgStoredEvents = {
    val msgCreatedEvent = buildMsgCreatedEvt(
      msgId, msgName, senderDID,
      sendMsg = sendMsg, statusCode, threadOpt, refMsgId)
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

  private def buildMsgCreatedEvt(msgId: MsgId,
                                 mType: String,
                                 senderDID: DidStr,
                                 sendMsg: Boolean,
                                 msgStatus: String,
                                 threadOpt: Option[Thread],
                                 LEGACY_refMsgId: Option[MsgId]=None): MsgCreated = {
    checkIfMsgAlreadyNotExists(msgId)
    MsgHelper.buildMsgCreatedEvt(
      msgId,
      mType,
      senderDID,
      sendMsg,
      msgStatus,
      threadOpt,
      LEGACY_refMsgId
    )
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
    msgStore.getMsgOpt(umds.uid).foreach { _ =>
      val msgDeliveryStatuses = getMsgDeliveryStatus(umds.uid)
      val deliveryStatusByDestination = msgDeliveryStatuses.get(umds.to)
      val existingFailedAttemptCount = deliveryStatusByDestination.map(_.failedAttemptCount).getOrElse(0)
      val newFailedAttemptCount =
        if (umds.statusCode == MSG_DELIVERY_STATUS_FAILED.statusCode) existingFailedAttemptCount + 1
        else existingFailedAttemptCount
      writeAndApply(MsgDeliveryStatusUpdated(umds.uid, umds.to, umds.statusCode,
        umds.statusDetail.getOrElse(Evt.defaultUnknownValueForStringType),
        getMillisForCurrentUTCZonedDateTime, newFailedAttemptCount))
    }
  }
}

case class GetMsgRespInternal(msgs: List[MsgDetail]) extends ActorMessage
case class UpdateMsgStatusRespInternal(uids: List[MsgId]) extends ActorMessage
case class StorePayloadParam(message: Array[Byte], metadata: Option[PayloadMetadata])