package com.evernym.verity.actor.persistence

import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_DYNAMODB_MESSAGE_DELETE_ATTEMPT_COUNT, AS_SERVICE_DYNAMODB_MESSAGE_DELETE_FAILED_COUNT, AS_SERVICE_DYNAMODB_MESSAGE_DELETE_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter

import scala.concurrent.duration.{SECONDS, _}
import scala.util.Random

/**
 * handles deletion of messages/events in batches
 */
trait DeleteMsgHandler { this: BasePersistentActor =>

  def msgDeleteCallbackHandler: Receive = {
    case DeleteEvents                => deleteEventsInBatches()
    case dms: DeleteMessagesSuccess  => handleDeleteMsgSuccess(dms)
    case dmf: DeleteMessagesFailure  => handleDeleteMsgFailure(dmf)
  }

  /**
   * an extended version of 'deleteMessages'
   * this takes care of deleting messages in batches
   * and increases/decreases number of messages (or batch size) accordingly
   * and will make sure it does delete messages eventually
   * @param toSeqNo sequence number till which events needs to be deleted
   */
  def deleteMessagesExtended(toSeqNo: Long): Unit = {
    deleteMsgProgress = deleteMsgProgress.copy(targetSeqNo = toSeqNo)
    deleteEventsInBatches()
  }

  private def deleteEventsInBatches(): Unit = {
    if (deleteMsgProgress.pendingCount > 0) {
      logger.debug(s"[$persistenceId] " +
        s"(lastSequenceNr: $lastSequenceNr) => " +
        s"deleteTargetSeqNr: ${deleteMsgProgress.targetSeqNo}, " +
        s"deletedTillSeqNr: ${deleteMsgProgress.deletedTillSeqNo}, " +
        s"nextBatchSize: ${deleteMsgProgress.batchSize}")
      MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_MESSAGE_DELETE_ATTEMPT_COUNT)
      deleteMessages(deleteMsgProgress.candidateSeqNoForDeletion)
    } else {
      completeMsgsDeletion()
    }
  }

  private def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    log.debug(s"[$persistenceId] total events deleted: ${dms.toSequenceNr}")
    onDeleteMessageSuccess(dms)
    MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_MESSAGE_DELETE_SUCCEED_COUNT)
    if (deleteMsgProgress.targetSeqNo == 0) {
      logger.info(s"[$persistenceId] events deleted (${dms.toSequenceNr})")
      completeMsgsDeletion()
    } else {
      handleBatchedDeleteMsgSuccess(dms)
    }
  }

  private def handleDeleteMsgFailure(dmf: DeleteMessagesFailure): Unit = {
    onDeleteMessageFailure(dmf)
    MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_MESSAGE_DELETE_FAILED_COUNT)
    if (deleteMsgProgress.targetSeqNo == 0) {
      //someone tried to just delete events at once (instead of using batched event deletion function)
      // so lets use the batched one to handle it gracefully
      logger.info(s"[$persistenceId] event deletion failed (${dmf.toSequenceNr}) " +
        s"(will fallback to batched event deletion)", (LOG_KEY_ERR_MSG, dmf.cause))
      deleteMessagesExtended(dmf.toSequenceNr)
    } else {
      handleBatchedDeleteMsgFailure(dmf)
    }
  }

  private def handleBatchedDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    logger.info(s"[$persistenceId] batched event deletion successful (${dms.toSequenceNr}/${deleteMsgProgress.targetSeqNo})")
    deleteMsgProgress = deleteMsgProgress.copy(deletedTillSeqNo = dms.toSequenceNr)
    if (deleteMsgProgress.pendingCount > 0) {
      adjustBatchParamsPostSuccess()
      scheduleNextDeletion()
    } else {
      completeMsgsDeletion()
    }
  }

  private def handleBatchedDeleteMsgFailure(dmf: DeleteMessagesFailure): Unit = {
    logger.info(s"[$persistenceId] batched event deletion failed (${deleteMsgProgress.deletedTillSeqNo}/${deleteMsgProgress.targetSeqNo}): ${dmf.toSequenceNr}", (LOG_KEY_ERR_MSG, dmf.cause))
    adjustBatchParamsPostFailure()
    adjustReceiveTimeoutIfRequired()
    scheduleNextDeletion()
  }

  private def scheduleNextDeletion(): Unit = {
    timers.startSingleTimer("deleteEvents", DeleteEvents, deleteMsgConfig.batchIntervalInSeconds.seconds)
  }

  private def completeMsgsDeletion(): Unit = {
    context.setReceiveTimeout(originalReceiveTimeout)
    postAllMsgsDeleted()
  }

  private def adjustBatchParamsPostSuccess(): Unit = {
    if (deleteMsgProgress.batchSize < deleteMsgConfig.maxBatchSize) {
      val newCandidateBatchSize = {
        val newBatchSize = deleteMsgProgress.batchSize * deleteMsgConfig.batchSizeMultiplier
        if (newBatchSize <= deleteMsgConfig.maxBatchSize) newBatchSize
        else deleteMsgConfig.maxBatchSize
      }
      deleteMsgProgress = deleteMsgProgress.copy(batchSize = newCandidateBatchSize)
    }
    val newBatchInterval =
      if (deleteMsgConfig.batchIntervalInSeconds > batchIntervalInSeconds)
        deleteMsgConfig.batchIntervalInSeconds / 2
      else batchIntervalInSeconds

    val finalNewBatchInterval =
      if (newBatchInterval < batchIntervalInSeconds) batchIntervalInSeconds
      else newBatchInterval

    deleteMsgConfig = deleteMsgConfig.copy(batchIntervalInSeconds = finalNewBatchInterval)
  }

  private def adjustBatchParamsPostFailure(): Unit = {
    val newCandidateBatchSize = deleteMsgProgress.batchSize / deleteMsgConfig.batchSizeMultiplier
    val finalNextBatchSize = if (newCandidateBatchSize >= initialBatchSize) newCandidateBatchSize else initialBatchSize
    deleteMsgProgress = deleteMsgProgress.copy(batchSize = finalNextBatchSize)
    val finalIntervalInSeconds = (deleteMsgConfig.batchIntervalInSeconds*2) + Random.nextInt(10)
    deleteMsgConfig = deleteMsgConfig.copy(batchIntervalInSeconds = finalIntervalInSeconds)
  }

  private def adjustReceiveTimeoutIfRequired(): Unit = {
    val nextBatchTimeout = Duration(deleteMsgConfig.batchIntervalInSeconds, SECONDS)
    if (context.receiveTimeout.lteq(nextBatchTimeout.plus(30.seconds))) {
      context.setReceiveTimeout(nextBatchTimeout.plus(60.seconds))
    }
  }
  //can be overridden by implementing class

  def isDeleteMsgInProgress: Boolean = deleteMsgProgress.pendingCount > 0

  def onDeleteMessageSuccess(dms: DeleteMessagesSuccess): Unit = {}
  def onDeleteMessageFailure(dmf: DeleteMessagesFailure): Unit = {}
  def postAllMsgsDeleted(): Unit = {}

  protected val initialBatchSize = 100
  protected val maxBatchSize = 1000

  protected val batchSizeMultiplier = 2
  protected val batchIntervalInSeconds = 5

  private val originalReceiveTimeout = context.receiveTimeout

  private var deleteMsgConfig: DeleteMsgConfig = DeleteMsgConfig(
    initialBatchSize, batchSizeMultiplier, maxBatchSize, batchIntervalInSeconds)
  private var deleteMsgProgress: DeleteMsgProgress = DeleteMsgProgress(
    0, 0, deleteMsgConfig.initialBatchSize)

}

/**
 *
 * @param initialBatchSize initial batch size for delete message
 * @param batchSizeMultiplier used with current batch size to figure out next batch size
 *                            either it will be multiplied or divide current batch size
 *                            to find out how much it should increase to current batch size
 * @param maxBatchSize maximum batch size
 * @param batchIntervalInSeconds interval in batches
 */
case class DeleteMsgConfig(initialBatchSize: Int,
                           batchSizeMultiplier: Int,
                           maxBatchSize: Int,
                           batchIntervalInSeconds: Int)

/**
 *
 * @param targetSeqNo final/target sequence number till which messages finally needs to be deleted
 * @param deletedTillSeqNo sequence number till which messages deleted so far
 * @param batchSize total number of events to be deleted in next attempt
 */
case class DeleteMsgProgress(targetSeqNo: Long,
                             deletedTillSeqNo: Long,
                             batchSize: Long) {

  def pendingCount: Long = targetSeqNo - deletedTillSeqNo

  def currentProgressString: String =
    s"final/target seqNo: $targetSeqNo, " +
    s"deleted till seqNo: $deletedTillSeqNo, " +
    s"nextSeqNoForDeletion: $batchSize"

  def candidateSeqNoForDeletion: Long = {
    val newSeqNo = deletedTillSeqNo + batchSize
    if (newSeqNo > targetSeqNo) targetSeqNo
    else newSeqNo
  }
}

case object DeleteEvents extends ActorMessage