package com.evernym.verity.actor.agent.user

import akka.event.LoggingReceive
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_PERSISTENCE_ID
import com.evernym.verity.actor.ActorMessageObject
import com.evernym.verity.actor.agent.msghandler.AgentMsgHandler
import com.evernym.verity.actor.agent.msghandler.incoming.{PackedMsgParam, RestMsgParam}
import com.evernym.verity.actor.agent.MsgPackVersion
import com.evernym.verity.actor.itemmanager.ItemCommonType.ItemId
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.protocol.engine.MsgId

import scala.concurrent.Future

/**
 * retries (re-sends) failed messages to their next hop
 * as of now only used for pairwise connections
 */
trait FailedMsgRetrier { this: AgentPersistentActor with AgentMsgHandler =>

  override final def receiveAgentCmd: Receive = agentCmdReceiver orElse retryCmdReceiver

  val retryCmdReceiver: Receive = LoggingReceive.withLabel("retryCmdReceiver") {
    case Init                                     => init()
    case CheckRetryJobScheduled                   => scheduleRetryFailedMsgsJobIfNotAlreadyScheduled()
    case RetryUndeliveredMsgs if sender == self   => resendUndeliveredMsgsIfAny()
  }

  self ! Init

  def init(): Unit = {
    scheduleRetryFailedMsgsJobIfNotAlreadyScheduled()
  }

  def scheduleRetryFailedMsgsJobIfNotAlreadyScheduled(): Unit = {
    if (retryFailedMsgsJob.isEmpty) {
      addItemToWatcherIfNotAlreadyDone()
      logger.debug(s"[$persistenceId] retryFailedMsgsJob is not scheduled at this moment, it will be scheduled now")
      retryFailedMsgsJob = {
        val jobId = "RetryUndeliveredMsgs"
        scheduleJob(
          jobId,
          scheduledJobInitialDelay,
          scheduledJobInterval,
          RetryUndeliveredMsgs
        )
        Option(jobId)
      }
    } else {
      logger.debug(s"[$persistenceId] retryFailedMsgsJob is already scheduled")
    }
  }

  def handleNoMsgToRetry(): Unit = {
    retryFailedMsgsJob.foreach { jobId =>
      stopScheduledJob(jobId)
      retryFailedMsgsJob = None
      logger.debug(s"[$persistenceId]: no failed message to retry, scheduled job stopped")
    }
    removeItemFromWatcherIfNotAlreadyDone()
  }

  def resendUndeliveredMsgsIfAny(): Unit = {
    updateUndeliveredMsgCountMetrics()
    val pendingMsgs = getMsgIdsEligibleForRetries
    logger.debug(s"[$persistenceId]: pending msgs during retry undelivered msg: " + pendingMsgs)
    if (pendingMsgs.nonEmpty) {
      getBatchedRecords(pendingMsgs).foreach { uid =>
        sendMsgToTheirAgent(uid, isItARetryAttempt = true, msgPackVersion(uid))
      }
    } else {
      handleNoMsgToRetry()
    }
  }

  override def preReceiveTimeoutCheck(): Boolean = {
    val pms = getMsgIdsEligibleForRetries
    logger.debug(s"[$persistenceId] during receive timeout, failed msgs to be retried: " + pms)
    if (pms.isEmpty) {
      removeItemFromWatcherIfNotAlreadyDone()
      logger.debug(s"[$persistenceId] actor will be stopped", (LOG_KEY_PERSISTENCE_ID, entityId))
      true
    } else {
      self ! RetryUndeliveredMsgs
      false
    }
  }

  def removeItemFromWatcherIfNotAlreadyDone(): Unit = {
    if (! isDeRegistered) {
      removeItemFromWatcher(entityId)
      isDeRegistered = true
    }
  }

  def addItemToWatcherIfNotAlreadyDone(): Unit = {
    if (isDeRegistered) {
      addItemToWatcher(entityId)
      isDeRegistered = false
    }
  }

  override def postCommandExecution(cmd: Any): Unit = {
    cmd match {
      case _: PackedMsgParam | _: RestMsgParam =>
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

  var isDeRegistered: Boolean = false
  var retryFailedMsgsJob: Option[JobId] = None
  lazy val defaultBatchSize: Int = appConfig.getConfigIntOption(FAILED_MSG_RETRIER_BATCH_SIZE).getOrElse(30)
  lazy val maxRetryCount: Int = appConfig.getConfigIntOption(FAILED_MSG_RETRIER_MAX_RETRY_COUNT).getOrElse(5)

  def msgPackVersion(msgId: MsgId): MsgPackVersion
  def batchSize: Option[Int] = None   //can be overridden by implementing class
  def scheduledJobInitialDelay: Int
  def scheduledJobInterval: Int
  def getMsgIdsEligibleForRetries: Set[MsgId]
  def updateUndeliveredMsgCountMetrics(): Unit
  def sendMsgToTheirAgent(uid: MsgId, isItARetryAttempt: Boolean, mpv: MsgPackVersion): Future[Any]
  def agentCmdReceiver: Receive
  def addItemToWatcher(itemId: ItemId): Unit
  def removeItemFromWatcher(itemId: ItemId): Unit
}

case object RetryUndeliveredMsgs extends ActorMessageObject
case object CheckRetryJobScheduled extends ActorMessageObject
case object Init extends ActorMessageObject