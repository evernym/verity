package com.evernym.verity.actor.persistence

import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.constants.LogKeyConstants.{LOG_KEY_ERR_MSG, LOG_KEY_PERSISTENCE_ID}
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_DYNAMODB_MESSAGE_DELETE_ATTEMPT_COUNT, AS_SERVICE_DYNAMODB_MESSAGE_DELETE_FAILED_COUNT, AS_SERVICE_DYNAMODB_MESSAGE_DELETE_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter

import scala.concurrent.duration._

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
      logger.debug(s"[$persistenceId] => " +
        s"final/target seqNo: ${deleteMsgProgress.targetSeqNo}, " +
        s"deleted till seqNo: ${deleteMsgProgress.deletedTillSeqNo}, " +
        s"nextSeqNoForDeletion: ${deleteMsgProgress.batchSize}")
      MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_MESSAGE_DELETE_ATTEMPT_COUNT)
      deleteMessages(deleteMsgProgress.candidateSeqNoForDeletion)
    } else {
      completeMsgsDeletion()
    }
  }

  private def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    onDeleteMessageSuccess(dms)

    MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_MESSAGE_DELETE_SUCCEED_COUNT)
    logger.debug(s"messages deleted", (LOG_KEY_PERSISTENCE_ID, persistenceId),
      ("seq_num", dms.toSequenceNr))
    deleteMsgProgress = deleteMsgProgress.copy(deletedTillSeqNo = dms.toSequenceNr)
    if (deleteMsgProgress.pendingCount > 0) {
      increaseBatchSize()
      scheduleNextDeletion()
    } else {
      completeMsgsDeletion()
    }
  }

  private def handleDeleteMsgFailure(dmf: DeleteMessagesFailure): Unit = {
    onDeleteMessageFailure(dmf)

    MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_MESSAGE_DELETE_FAILED_COUNT)
    logger.info(s"could not delete old messages", (LOG_KEY_PERSISTENCE_ID, persistenceId),
      ("seq_num", dmf.toSequenceNr), (LOG_KEY_ERR_MSG, dmf.cause))
    decreaseBatchSize()
    scheduleNextDeletion()
  }

  private def scheduleNextDeletion(): Unit = {
    timers.startSingleTimer("deleteEvents", DeleteEvents, deleteMsgConfig.batchIntervalInSeconds.seconds)
  }

  private def completeMsgsDeletion(): Unit = postAllMsgsDeleted()

  private def increaseBatchSize(): Unit = {
    if (deleteMsgProgress.batchSize < deleteMsgConfig.maxBatchSize) {
      val newCandidateBatchSize = {
        val newBatchSize = deleteMsgProgress.batchSize * deleteMsgConfig.batchSizeMultiplier
        if (newBatchSize <= deleteMsgConfig.maxBatchSize) newBatchSize
        else deleteMsgConfig.maxBatchSize
      }
      deleteMsgProgress = deleteMsgProgress.copy(batchSize = newCandidateBatchSize)
    }
  }

  private def decreaseBatchSize(): Unit = {
    val newCandidateBatchSize = deleteMsgProgress.batchSize / deleteMsgConfig.batchSizeMultiplier
    if (newCandidateBatchSize > 0)
      deleteMsgProgress = deleteMsgProgress.copy(batchSize = newCandidateBatchSize)
  }

  private var deleteMsgProgress: DeleteMsgProgress = DeleteMsgProgress(0, 0, deleteMsgConfig.initialBatchSize)

  //can be overridden by implementing class

  def onDeleteMessageSuccess(dms: DeleteMessagesSuccess): Unit = {}
  def onDeleteMessageFailure(dmf: DeleteMessagesFailure): Unit = {}
  def postAllMsgsDeleted(): Unit = {}

  protected lazy val deleteMsgConfig: DeleteMsgConfig = DeleteMsgConfig(50, 2, 1000, 10)
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