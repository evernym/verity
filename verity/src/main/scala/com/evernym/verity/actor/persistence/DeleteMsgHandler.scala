package com.evernym.verity.actor.persistence

import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess}
import com.evernym.verity.constants.LogKeyConstants.{LOG_KEY_ERR_MSG, LOG_KEY_PERSISTENCE_ID}
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_DYNAMODB_MESSAGE_DELETE_ATTEMPT_COUNT, AS_SERVICE_DYNAMODB_MESSAGE_DELETE_FAILED_COUNT, AS_SERVICE_DYNAMODB_MESSAGE_DELETE_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter


trait DeleteMsgHandler { this: BasePersistentActor =>

  def msgDeleteCallbackHandler: Receive = {
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
    deleteMsgsTillSeqNo = toSeqNo
    deleteEventsInBatches()
  }

  private def deleteEventsInBatches(): Unit = {
    val nextBatchToSeqNo = msgDeletedTillSeqNo + deleteEventBatchSize
    val toSeqNo = if (nextBatchToSeqNo >= deleteMsgsTillSeqNo) deleteMsgsTillSeqNo else nextBatchToSeqNo
    if (toSeqNo > 0) {
      logger.debug(s"[$persistenceId] => deleteMsgsTillSeqNo: $deleteMsgsTillSeqNo, " +
        s"msgDeletedTillSeqNo -> $msgDeletedTillSeqNo, nowDeleteToSeqNo-> $toSeqNo")
      MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_MESSAGE_DELETE_ATTEMPT_COUNT)
      deleteMessages(toSeqNo)
    } else {
      completeMsgsDeletion()
    }
  }

  protected def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_MESSAGE_DELETE_SUCCEED_COUNT)
    logger.debug(s"messages deleted", (LOG_KEY_PERSISTENCE_ID, persistenceId),
      ("seq_num", deleteMsgsTillSeqNo))
    if (deleteMsgsTillSeqNo > 0) {
      msgDeletedTillSeqNo = dms.toSequenceNr
      deleteEventBatchSize += deleteEventBatchSizeModifier
      if (dms.toSequenceNr == deleteMsgsTillSeqNo) {
        completeMsgsDeletion()
      } else {
        deleteEventsInBatches()
      }
    }
  }

  protected def handleDeleteMsgFailure(dmf: DeleteMessagesFailure): Unit = {
    MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_MESSAGE_DELETE_FAILED_COUNT)
    logger.info(s"could not delete old messages", (LOG_KEY_PERSISTENCE_ID, persistenceId),
      ("seq_num", dmf.toSequenceNr), (LOG_KEY_ERR_MSG, dmf.cause))
    if (deleteMsgsTillSeqNo > 0) {
      deleteEventBatchSize -= deleteEventBatchSizeModifier * 2
      if (deleteEventBatchSize <= 0) deleteEventBatchSize = deleteEventBatchSizeModifier
      deleteEventsInBatches()
    }
  }

  /**
   * implementing class can override this
   */
  def postAllMsgsDeleted(): Unit = { }

  private def completeMsgsDeletion(): Unit = {
    deleteMsgsTillSeqNo = 0
    postAllMsgsDeleted()
  }

  /**
   * sequence number till which messages is already deleted
   */
  var msgDeletedTillSeqNo: Long = 0

  /**
   * sequence number till which messages needed to be deleted
   */
  var deleteMsgsTillSeqNo: Long = 0

  var deleteEventBatchSize = 50
  val deleteEventBatchSizeModifier = 50
}
