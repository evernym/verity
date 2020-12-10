package com.evernym.verity.actor.agent.maintenance

import akka.persistence.{DeleteMessagesFailure, DeleteMessagesSuccess}
import com.evernym.verity.actor.ActorMessageObject
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.config.CommonConfig
import com.evernym.verity.constants.LogKeyConstants.{LOG_KEY_ERR_MSG, LOG_KEY_PERSISTENCE_ID}
import com.evernym.verity.logging.LoggingUtil
import com.typesafe.scalalogging.Logger

trait ActorStateCleanupBase { this: BasePersistentActor =>

  def deleteEventsInBatches(lastDeletedToSeqNo: Long = toSeqNoDeleted): Unit = {
    val nextBatchToSeqNo = lastDeletedToSeqNo + deleteEventBatchSize
    val toSeqNo = if (nextBatchToSeqNo > lastSequenceNr) lastSequenceNr else nextBatchToSeqNo
    if (toSeqNo > 0) {
      logger.debug(s"[$persistenceId] => totalEvents: $lastSequenceNr, " +
        s"lastDeletedToSeqNo -> $lastDeletedToSeqNo, nowDeleteToSeqNo-> $toSeqNo")
      deleteMessages(toSeqNo)
    } else {
      postAllEventDeleted()
    }
  }

  override def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    toSeqNoDeleted = dms.toSequenceNr
    deleteEventBatchSize += deleteEventBatchSizeModifier
    if (dms.toSequenceNr == lastSequenceNr) {
      postAllEventDeleted()
    } else {
      deleteEventsInBatches(dms.toSequenceNr)
    }
  }

  override def handleDeleteMsgFailure(dmf: DeleteMessagesFailure): Unit = {
    logger.info(s"could not delete old messages", (LOG_KEY_PERSISTENCE_ID, persistenceId),
      ("seq_num", dmf.toSequenceNr), (LOG_KEY_ERR_MSG, dmf.cause))
    deleteEventBatchSize -= deleteEventBatchSizeModifier*2
    if (deleteEventBatchSize <= 0) deleteEventBatchSize = deleteEventBatchSizeModifier
    deleteEventsInBatches()
  }

  def isActorStateCleanupEnabled: Boolean =
    appConfig
      .getConfigBooleanOption(CommonConfig.AGENT_ACTOR_STATE_CLEANUP_ENABLED)
      .getOrElse(false)

  //this is internal actor for short period of time and doesn't contain any sensitive data
  override def persistenceEncryptionKey: String = this.getClass.getSimpleName

  protected val logger: Logger = LoggingUtil.getLoggerByName(this.getClass.getSimpleName)

  def postAllEventDeleted(): Unit

  var toSeqNoDeleted: Long = 0
  var deleteEventBatchSize = 50
  val deleteEventBatchSizeModifier = 50
}


case object ProcessPending extends ActorMessageObject