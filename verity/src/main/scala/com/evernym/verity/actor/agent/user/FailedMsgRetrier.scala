package com.evernym.verity.actor.agent.user

import akka.event.LoggingReceive
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_PERSISTENCE_ID
import com.evernym.verity.actor.{ActorMessage, HasAppConfig}
import com.evernym.verity.actor.agent.{HasSingletonParentProxy, MsgPackFormat}
import com.evernym.verity.actor.cluster_singleton.watcher.CheckWatchedItem
import com.evernym.verity.actor.itemmanager.ItemManagerEntityHelper
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.protocol.container.actor.UpdateMsgDeliveryStatus
import com.evernym.verity.protocol.engine.MsgId

import scala.concurrent.Future

/**
 * retries (re-sends) failed messages to their next hop
 * as of now only used for pairwise connections
 */
trait FailedMsgRetrier
  extends HasSingletonParentProxy { this: BasePersistentActor with HasAppConfig =>

  val retryCmdReceiver: Receive = LoggingReceive.withLabel("retryCmdReceiver") {
    case FailedMsgRetrierInit   => init()
    case CheckWatchedItem       => handleCheckWatchedItem()
    case RetryUndeliveredMsgs   => resendUndeliveredMsgsIfAny()
  }

  self ! FailedMsgRetrierInit

  private def init(): Unit = {
    scheduleRetryFailedMsgsJobIfNotAlreadyScheduled()
  }

  private def handleCheckWatchedItem(): Unit = {
    isRegistered = true
    scheduleRetryFailedMsgsJobIfNotAlreadyScheduled()
  }

  private def scheduleRetryFailedMsgsJobIfNotAlreadyScheduled(): Unit = {
    if (retryFailedMsgsJob.isEmpty) {
      logger.debug(s"[$persistenceId] retryFailedMsgsJob is not scheduled at this moment, it will be scheduled now")
      retryFailedMsgsJob = {
        val jobId = "RetryUndeliveredMsgs"
        scheduleJob(
          jobId,
          scheduledJobInterval,
          RetryUndeliveredMsgs
        )
        Option(jobId)
      }
    } else {
      logger.debug(s"[$persistenceId] retryFailedMsgsJob is already scheduled")
    }
  }

  private def handleNoMsgToRetry(): Unit = {
    retryFailedMsgsJob.foreach { jobId =>
      stopScheduledJob(jobId)
      retryFailedMsgsJob = None
      logger.debug(s"[$persistenceId]: no failed message to retry, scheduled job stopped")
    }
    removeItemFromWatcher()
  }

  private def resendUndeliveredMsgsIfAny(): Unit = {
    val pendingMsgs = getMsgIdsEligibleForRetries
    logger.debug(s"[$persistenceId]: pending msgs during retry undelivered msg: " + pendingMsgs)
    if (pendingMsgs.nonEmpty) {
      getBatchedRecords(pendingMsgs).foreach { uid =>
        log.debug("send msg to their agent: " + uid)
        sendMsgToTheirAgent(uid, isItARetryAttempt = true, msgPackFormat(uid))
      }
    } else {
      handleNoMsgToRetry()
    }
  }

  override def postCommandExecution(cmd: Any): Unit = {
    cmd match {
      case uds: UpdateMsgDeliveryStatus if uds.isFailed =>
        addItemToWatcher()
        scheduleRetryFailedMsgsJobIfNotAlreadyScheduled()
      case _ => //nothing to do
    }
  }

  private def getBatchedRecords(pendingMsgs: Set[MsgId]): Set[MsgId] = {
    val currentBatchSize = batchSize.getOrElse(defaultBatchSize)
    if (currentBatchSize >=0 ) {
      pendingMsgs.take(currentBatchSize)
    } else pendingMsgs
  }

  override def preReceiveTimeoutCheck(): Boolean = {
    val pms = getMsgIdsEligibleForRetries
    logger.debug(s"[$persistenceId] during receive timeout, failed msgs to be retried: " + pms)
    if (pms.isEmpty) {
      removeItemFromWatcher()
      logger.debug(s"[$persistenceId] actor will be stopped", (LOG_KEY_PERSISTENCE_ID, entityId))
      true
    } else {
      self ! RetryUndeliveredMsgs
      false
    }
  }

  def addItemToWatcher(): Unit = {
    itemManagerEntityHelper.register()
    log.debug("item added to watcher: " + entityId)
  }

  def removeItemFromWatcher(): Unit = {
    itemManagerEntityHelper.deregister()
    log.debug("item removed from watcher: " + entityId)
  }

  private var isRegistered: Boolean = false
  private var retryFailedMsgsJob: Option[JobId] = None
  private lazy val defaultBatchSize: Int = appConfig.getIntOption(FAILED_MSG_RETRIER_BATCH_SIZE).getOrElse(30)

  lazy val maxRetryCount: Int = appConfig.getIntOption(FAILED_MSG_RETRIER_MAX_RETRY_COUNT).getOrElse(5)

  import akka.actor.typed.scaladsl.adapter._
  lazy val itemManagerEntityHelper = new ItemManagerEntityHelper(entityId, entityType, context.system.toTyped)

  def msgPackFormat(msgId: MsgId): MsgPackFormat
  def batchSize: Option[Int] = None   //can be overridden by implementing class
  def scheduledJobInterval: Int
  def getMsgIdsEligibleForRetries: Set[MsgId]
  def sendMsgToTheirAgent(uid: MsgId, isItARetryAttempt: Boolean, mpf: MsgPackFormat): Future[Any]
}

case object RetryUndeliveredMsgs extends ActorMessage
case object FailedMsgRetrierInit extends ActorMessage
