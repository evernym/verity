package com.evernym.verity.actor.agent.user.msgstore

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, InternalServerErrorException}
import com.evernym.verity.util2.Status.{DATA_NOT_FOUND, MSG_DELIVERY_STATUS_SENT, MSG_STATUS_CREATED, MSG_STATUS_RECEIVED, MSG_STATUS_REVIEWED, MSG_STATUS_SENT}
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent._
import com.evernym.verity.actor.agent.user.MsgHelper
import com.evernym.verity.agentmsg.msgfamily.pairwise.GetMsgsReqMsg
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.Constants.YES
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.{MsgId, MsgName, RefMsgId}
import com.evernym.verity.protocol.protocols.MsgDetail
import kamon.metric.MeasurementUnit
import org.slf4j.LoggerFactory

import scala.collection.immutable.ListSet


class MsgStore(appConfig: AppConfig,
               msgStateAPIProvider: MsgStateAPIProvider,
               failedMsgTracker: Option[FailedMsgTracker]){
  /**
   * imagine below collection of messages (each of the below line is a message record with different fields)
   *  uid1, conReq,       refMsgId=uid2 ,...
   *  uid2, conReqAnswer, ...           ,...
   *
   *
   * for above example use case, the 'refMsgIdToMsgId' mapping will look like this:
   *  uid2 -> uid1
   */
  private var refMsgIdToMsgId: Map[RefMsgId, MsgId] = Map.empty
  private var unseenMsgIds: Set[MsgId] = Set.empty
  private var seenMsgIds: Set[MsgId] = Set.empty
  private val logger = LoggerFactory.getLogger("MsgStore")

  private var retainedDeliveredMsgIds: ListSet[MsgId] = ListSet.empty
  private var retainedUndeliveredMsgIds: ListSet[MsgId] = ListSet.empty
  private var removedMsgsCount = 0

  /**
   * mapping between MsgName (message type name, like: connection req, cred etc)
   * and it's expiration time in seconds
   */
  type Seconds = Int
  private var msgExpirationTime: Map[MsgName, Seconds] = Map.empty

  def allUnseenMsgIds: Set[MsgId] = unseenMsgIds
  def getReplyToMsgId(msgId: MsgId): Option[MsgId] = refMsgIdToMsgId.get(msgId)
  def getMsgReq(msgId: MsgId): Msg = msgStateAPIProvider.getMsgReq(msgId)
  def getMsgOpt(msgId: MsgId): Option[Msg] = msgStateAPIProvider.getMsgOpt(msgId)
  def getMsgDetails(msgId: MsgId): Map[String, String] = msgStateAPIProvider.getMsgDetails(msgId)
  def getMsgDeliveryStatus(msgId: MsgId): Map[String, MsgDeliveryDetail] =msgStateAPIProvider.getMsgDeliveryStatus(msgId)
  def getMsgPayload(msgId: MsgId): Option[PayloadWrapper] = msgStateAPIProvider.getMsgPayload(msgId)

  def getMsgs(gmr: GetMsgsReqMsg): List[MsgDetail] = {
    val msgAndDelivery = msgStateAPIProvider.msgAndDeliveryReq
    val msgIds = gmr.uids.getOrElse(List.empty).map(_.trim).toSet
    val statusCodes = gmr.statusCodes.getOrElse(List.empty).map(_.trim).toSet
    val filteredMsgs = {
      if (msgIds.isEmpty && statusCodes.size == 1 && statusCodes.head == MSG_STATUS_RECEIVED.statusCode) {
        allUnseenMsgIds.map(mId => mId -> getMsgOpt(mId))
          .filter(_._2.nonEmpty)
          .map(r => r._1 -> r._2.get)
          .toMap
      } else {
        val uidFilteredMsgs = if (msgIds.nonEmpty) {
          msgIds.map(mId => mId -> getMsgOpt(mId))
            .filter(_._2.nonEmpty)
            .map(r => r._1 -> r._2.get)
            .toMap
        } else msgAndDelivery.msgs
        if (statusCodes.nonEmpty) uidFilteredMsgs.filter(m => statusCodes.contains(m._2.statusCode))
        else uidFilteredMsgs
      }
    }
    val accumulatedMsgs = filteredMsgs.map { case (uid, msg) =>
      val payloadWrapper = if (gmr.excludePayload.contains(YES)) None else msgAndDelivery.msgPayloads.get(uid)
      val payload = payloadWrapper.map(_.msg)
      MsgParam(
        MsgDetail(uid, msg.`type`, msg.senderDID, msg.statusCode, msg.refMsgId, msg.thread, payload, Set.empty),
        msg.creationTimeInMillis
      )
    }
      .toSeq
      .sortWith(_.creationTimeInMillis < _.creationTimeInMillis)    //sorting by creation order
      .foldLeft(AccumulatedMsgs(msgs = List.empty[MsgDetail], totalPayloadSize = 0)) { case (accumulated, next) =>
        if (accumulated.totalPayloadSize + next.payloadSize < getMsgsLimit) {
          accumulated.withNextAdded(next)
        } else {
          accumulated
        }
      }

    logger.debug(s"total payload size of messages ${accumulatedMsgs.totalPayloadSize}, " +
      s"total messages ${accumulatedMsgs.msgs.size}")

    accumulatedMsgs.msgs
  }

  def handleMsgCreated(mc: MsgCreated): Unit = {
    if (isTotalMsgSizeLimitReached) {
      removeExtraMsgsToAccommodateNewMsg()
    }
    val receivedOrders = mc.thread.map(th => th.receivedOrders.map(ro => ro.from -> ro.order).toMap).getOrElse(Map.empty)
    val msgThread = mc.thread.map(th => Thread(
      Evt.getOptionFromValue(th.id),
      Evt.getOptionFromValue(th.parentId),
      Option(th.senderOrder),
      receivedOrders))
    val msg = Msg(
      mc.typ,
      mc.senderDID,
      mc.statusCode,
      mc.creationTimeInMillis,
      mc.lastUpdatedTimeInMillis,
      Evt.getOptionFromValue(mc.refMsgId),
      msgThread,
      mc.sendMsg)
    msgStateAPIProvider.addToMsgs(mc.uid, msg)
    retainedUndeliveredMsgIds += mc.uid
    updateMsgIndexes(mc.uid, msg)
    updateFailedMsgTracker(mc.uid)
  }

  def handleMsgAnswered(ma: MsgAnswered): Unit = {
    msgStateAPIProvider.getMsgOpt(ma.uid).foreach { msg =>
      val updated = msg.copy(statusCode = ma.statusCode, refMsgId = Option(ma.refMsgId),
        lastUpdatedTimeInMillis = ma.lastUpdatedTimeInMillis)
      msgStateAPIProvider.addToMsgs(ma.uid, updated)
      updateMsgIndexes(ma.uid, updated)
    }
  }

  def handleMsgStatusUpdated(msu: MsgStatusUpdated): Unit = {
    msgStateAPIProvider.getMsgOpt(msu.uid).foreach { msg =>
      val updated = msg.copy(statusCode = msu.statusCode, lastUpdatedTimeInMillis =
        msu.lastUpdatedTimeInMillis)
      msgStateAPIProvider.addToMsgs(msu.uid, updated)
      updateMsgIndexes(msu.uid, updated)
    }
  }

  def handleMsgDeliveryStatusUpdated(mdsu: MsgDeliveryStatusUpdated): Unit = {
    msgStateAPIProvider.getMsgOpt(mdsu.uid).foreach { msg =>
      val candidateForRemoval = isMsgOlderThanAllowedTime(msg)
      val msgConsideredToBeDelivered = isMsgConsideredToBeDelivered(mdsu, msg)
      if (msgConsideredToBeDelivered && candidateForRemoval) {
        removeMsgsFromState(Set(mdsu.uid))
      } else  {
        if (msgConsideredToBeDelivered) {
          retainedDeliveredMsgIds += mdsu.uid
          retainedUndeliveredMsgIds -= mdsu.uid
        }
        val isDeliveryStatusSent = mdsu.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode
        val isMsgStatusCreated = msg.statusCode == MSG_STATUS_CREATED.statusCode
        if (isDeliveryStatusSent && isMsgStatusCreated) {
          val updated = msg.copy(statusCode = MSG_STATUS_SENT.statusCode)
          msgStateAPIProvider.addToMsgs(mdsu.uid, updated)
        }
        val emds = msgStateAPIProvider.getMsgDeliveryStatus(mdsu.uid)
        val newMds = MsgDeliveryDetail(mdsu.statusCode, Evt.getOptionFromValue(mdsu.statusDetail),
          mdsu.failedAttemptCount, mdsu.lastUpdatedTimeInMillis)
        val nmds = emds ++ Map(mdsu.to -> newMds)
        msgStateAPIProvider.addToDeliveryStatus(mdsu.uid, nmds)
        updateFailedMsgTracker(mdsu.uid, Option(mdsu))
      }
    }
  }

  def handleMsgDetailAdded(mda: MsgDetailAdded): Unit = {
    msgStateAPIProvider.getMsgOpt(mda.uid).foreach { _ =>
      val emd = msgStateAPIProvider.getMsgDetails(mda.uid)
      val nmd = emd ++ Map(mda.name -> mda.value)
      msgStateAPIProvider.addToMsgDetails(mda.uid, nmd)
    }
  }

  def handleMsgPayloadStored(mps: MsgPayloadStored): Unit = {
    msgStateAPIProvider.getMsgOpt(mps.uid).foreach { _ =>
      val metadata = mps.payloadContext.map { pc => PayloadMetadata(pc.msgType, pc.msgPackFormat) }
      msgStateAPIProvider.addToMsgPayloads(mps.uid, PayloadWrapper(mps.payload.toByteArray, metadata))
    }
  }

  def handleMsgExpiryTimeUpdated(metu: MsgExpirationTimeUpdated): Unit = {
    msgExpirationTime = msgExpirationTime ++ Map(metu.msgType -> metu.timeInSeconds)
  }

  private def allMsgs: Map[String, Msg] = msgStateAPIProvider.msgAndDeliveryReq.msgs

  private def isTotalMsgSizeLimitReached: Boolean = allMsgs.size >= totalMsgsToRetain

  private def removeExtraMsgsToAccommodateNewMsg(): Unit = {
    val msgsToBeRemovedCount = allMsgs.size - totalMsgsToRetain + 1
    val deliveredMsgIdsToBeRemoved = retainedDeliveredMsgIds.take(msgsToBeRemovedCount)
    val undeliveredMsgIdsToBeRemoved = retainedUndeliveredMsgIds.take(msgsToBeRemovedCount - deliveredMsgIdsToBeRemoved.size)
    val msgIds = deliveredMsgIdsToBeRemoved ++ undeliveredMsgIdsToBeRemoved
    removeMsgsFromState(msgIds)
  }

  private def updateMsgIndexes(msgId: MsgId, msg: Msg): Unit = {
    if (msg.statusCode == MSG_STATUS_RECEIVED.statusCode) {
      unseenMsgIds += msgId
    } else if (MsgHelper.seenMsgStatusCodes.contains(msg.statusCode)) {
      seenMsgIds += msgId
      unseenMsgIds -= msgId
    }
    msg.refMsgId.foreach { refMsgId =>
      refMsgIdToMsgId += refMsgId -> msgId
    }
  }

  private def updateFailedMsgTracker(msgId: MsgId, mdsu: Option[MsgDeliveryStatusUpdated]=None): Unit = {
    msgStateAPIProvider.getMsgOpt(msgId).foreach { msg =>
      val deliveryStatus = msgStateAPIProvider.getMsgDeliveryStatus(msgId)
      failedMsgTracker.foreach(_.updateDeliveryState(msgId, msg, deliveryStatus, mdsu))
    }
  }

  private def isMsgOlderThanAllowedTime(msg: Msg): Boolean = {
    val timeSinceCreation = ChronoUnit.DAYS.between(msg.creationDateTime, ZonedDateTime.now())
    timeSinceCreation > timeToRetainDeliveredMsgsInDays
  }

  private def isMsgConsideredToBeDelivered(mdsu: MsgDeliveryStatusUpdated, msg: Msg): Boolean = {
    val isMsgSuccessfullyDelivered = mdsu.statusCode == MSG_DELIVERY_STATUS_SENT.statusCode
    val isMsgDeliveryAcknowledged = msg.statusCode == MSG_STATUS_REVIEWED.statusCode
    if (isMsgAckNeeded && isMsgDeliveryAcknowledged) true
    else if (! isMsgAckNeeded && isMsgSuccessfullyDelivered) true
    else false
  }

  private def removeMsgsFromState(msgIds: Set[MsgId]): Unit = {
    if (isStateMessagesCleanupEnabled) {
      msgStateAPIProvider.removeFromMsgs(msgIds)
      msgStateAPIProvider.removeFromMsgDetails(msgIds)
      msgStateAPIProvider.removeFromMsgPayloads(msgIds)
      msgStateAPIProvider.removeFromMsgDeliveryStatus(msgIds)
      removeMsgsFromLocalIndexState(msgIds)
      failedMsgTracker.foreach { fmt => fmt.removeMsgs(msgIds) }
      removedMsgsCount += 1
    }
  }

  def updateMsgStateMetrics(): Unit = {
    if (removedMsgsCount > 0) {
      MetricsWriter.histogramApi.record(AS_AKKA_ACTOR_AGENT_RETAINED_MSGS, MeasurementUnit.none, allMsgs.size)
      MetricsWriter.histogramApi.record(AS_AKKA_ACTOR_AGENT_REMOVED_MSGS, MeasurementUnit.none, removedMsgsCount)
      MetricsWriter.histogramApi.record(AS_AKKA_ACTOR_AGENT_WITH_MSGS_REMOVED, MeasurementUnit.none, 1)
    }
  }

  private def removeMsgsFromLocalIndexState(msgIds: Set[MsgId]): Unit = {
    seenMsgIds --= msgIds
    unseenMsgIds --= msgIds
    retainedDeliveredMsgIds --= msgIds
    retainedUndeliveredMsgIds --= msgIds
    refMsgIdToMsgId = refMsgIdToMsgId.filterNot { case (refMsgId, msgId) =>
      msgIds.contains(refMsgId) || msgIds.contains(msgId)
    }
  }

  /**
   * if this is VAS, then it doesn't acknowledges message receipt
   * and hence it would be ok to just consider message delivery status to
   * know if that message can be considered as delivered and acknowledged too
   */
  private lazy val isMsgAckNeeded: Boolean =
    ! appConfig
      .getStringOption(AKKA_SHARDING_REGION_NAME_USER_AGENT)
      .contains("VerityAgent")

  private lazy val timeToRetainDeliveredMsgsInDays: Int =
    appConfig
      .getIntOption(AGENT_STATE_MESSAGES_CLEANUP_DAYS_TO_RETAIN_DELIVERED_MSGS).getOrElse(14)

  private lazy val totalMsgsToRetain: Int =
    appConfig
      .getIntOption(AGENT_STATE_MESSAGES_CLEANUP_TOTAL_MSGS_TO_RETAIN).getOrElse(75)

  private lazy val isStateMessagesCleanupEnabled: Boolean =
    appConfig.getBooleanOption(AGENT_STATE_MESSAGES_CLEANUP_ENABLED).getOrElse(false)

  private lazy val getMsgsLimit: Int =
    appConfig.getIntOption(AGENT_STATE_MESSAGES_GET_MSGS_LIMIT).getOrElse(920000)
}

trait MsgStateAPIProvider {

  def msgAndDelivery: Option[MsgAndDelivery]
  def updateMsgAndDelivery(msgAndDelivery: MsgAndDelivery): Unit

  def msgAndDeliveryReq: MsgAndDelivery = msgAndDelivery.getOrElse(MsgAndDelivery())

  def withMsgAndDeliveryState[T](f: MsgAndDelivery => T): T = {
    f(msgAndDeliveryReq)
  }

  def addToMsgs(msgId: MsgId, msg: Msg): Unit = {
    withMsgAndDeliveryState { msgAndDelivery =>
      updateMsgAndDelivery(msgAndDelivery.copy(msgs = msgAndDelivery.msgs ++ Map(msgId -> msg)))
    }
  }

  def addToMsgDetails(msgId: MsgId, attribs: Map[String, String]): Unit = {
    withMsgAndDeliveryState { msgAndDelivery =>
      val newMsgAttribs = msgAndDelivery.msgDetails.getOrElse(msgId, MsgAttribs()).attribs ++ attribs
      updateMsgAndDelivery(
        msgAndDelivery.copy(msgDetails = msgAndDelivery.msgDetails ++ Map(msgId -> MsgAttribs(newMsgAttribs)))
      )
    }
  }

  def addToMsgPayloads(msgId: MsgId, payloadWrapper: PayloadWrapper): Unit = {
    withMsgAndDeliveryState { msgAndDelivery =>
      updateMsgAndDelivery(
        msgAndDelivery.copy(msgPayloads = msgAndDelivery.msgPayloads ++ Map(msgId -> payloadWrapper))
      )
    }
  }

  def addToDeliveryStatus(msgId: MsgId, deliveryDetails: Map[String, MsgDeliveryDetail]): Unit = {
    withMsgAndDeliveryState { msgAndDelivery =>
      val newMsgDeliveryByDest =
        msgAndDelivery.msgDeliveryStatus.getOrElse(msgId, MsgDeliveryByDest()).msgDeliveryStatus ++ deliveryDetails
      updateMsgAndDelivery(
        msgAndDelivery.copy(msgDeliveryStatus =
          msgAndDelivery.msgDeliveryStatus ++ Map(msgId -> MsgDeliveryByDest(newMsgDeliveryByDest)))
      )
    }
  }

  def removeFromMsgs(msgIds: Set[MsgId]): Unit = {
    withMsgAndDeliveryState { msgAndDelivery =>
      updateMsgAndDelivery(msgAndDelivery.copy(msgs = msgAndDelivery.msgs -- msgIds))
    }
  }

  def removeFromMsgDetails(msgIds: Set[MsgId]): Unit = {
    withMsgAndDeliveryState { msgAndDelivery =>
      updateMsgAndDelivery(msgAndDelivery.copy(msgDetails = msgAndDelivery.msgDetails -- msgIds))
    }
  }

  def removeFromMsgPayloads(msgIds: Set[MsgId]): Unit = {
    withMsgAndDeliveryState { msgAndDelivery =>
      updateMsgAndDelivery(msgAndDelivery.copy(msgPayloads = msgAndDelivery.msgPayloads -- msgIds))
    }
  }

  def removeFromMsgDeliveryStatus(msgIds: Set[MsgId]): Unit = {
    withMsgAndDeliveryState { msgAndDelivery =>
      updateMsgAndDelivery(msgAndDelivery.copy(msgDeliveryStatus = msgAndDelivery.msgDeliveryStatus -- msgIds))
    }
  }

  def getMsgReq(uid: MsgId): Msg = getMsgOpt(uid).getOrElse(
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(s"msg not found with uid: $uid")))

  def getMsgOpt(msgId: MsgId): Option[Msg] = {
    msgAndDeliveryReq.msgs.get(msgId)
  }

  def getMsgPayloadReq(uid: MsgId): PayloadWrapper = getMsgPayload(uid).getOrElse(
    throw new InternalServerErrorException(DATA_NOT_FOUND.statusCode, Option("payload not found"))
  )

  def getMsgPayload(msgId: MsgId): Option[PayloadWrapper] =
    msgAndDeliveryReq.msgPayloads.get(msgId)

  def getMsgDetails(msgId: MsgId): Map[MsgId, String] = {
    msgAndDeliveryReq.msgDetails.getOrElse(msgId, MsgAttribs()).attribs
  }

  def getMsgDeliveryStatus(msgId: MsgId): Map[MsgId, MsgDeliveryDetail] = {
    msgAndDeliveryReq.msgDeliveryStatus.getOrElse(msgId, MsgDeliveryByDest()).msgDeliveryStatus
  }

}

case class MsgParam(msgDetail: MsgDetail, creationTimeInMillis: Long) {
  def payloadSize: Int = msgDetail.payload.map(_.length).getOrElse(0)
}

case class AccumulatedMsgs(msgs: List[MsgDetail], totalPayloadSize: Int) {
  def withNextAdded(next: MsgParam): AccumulatedMsgs = {
    AccumulatedMsgs(msgs :+ next.msgDetail, totalPayloadSize + next.payloadSize)
  }
}