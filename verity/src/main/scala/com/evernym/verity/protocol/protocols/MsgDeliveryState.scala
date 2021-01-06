package com.evernym.verity.protocol.protocols

import com.evernym.verity.Status.{MSG_DELIVERY_STATUS_FAILED, MSG_DELIVERY_STATUS_SENT}
import com.evernym.verity.actor.MsgDeliveryStatusUpdated
import com.evernym.verity.actor.agent.{Msg, MsgDeliveryDetail}
import com.evernym.verity.protocol.engine.{DID, MsgId}


/**
 * State holds information about Message Delivery for failed and retriable messages
 * @param maxRetryAttempts maximum retry attempts for any failed message
 * @param retryEligibilityCriteriaProvider function which provides RetryEligibilityCriteria object
 *                                         user agent pairwise may have different criteria than user agent
 *                                         thats why it is provided by the class which wants to use this.
 */
class MsgDeliveryState(maxRetryAttempts: Int, retryEligibilityCriteriaProvider: () => RetryEligibilityCriteria) {

  type FailedAttemptCount = Int

  private var failedMsgs: Map[MsgId, FailedAttemptCount] = Map.empty
  private var eligibleForRetries: Set[MsgId] = Set.empty
  private var undeliveredMsgs: Set[MsgId] = Set.empty

  def updateDeliveryState(msgId: MsgId,
                          msg: Msg,
                          deliveryStatus: Map[String, MsgDeliveryDetail],
                          updatedDeliveryStatus: Option[MsgDeliveryStatusUpdated]=None): Unit = {

    updatedDeliveryStatus.foreach { newDeliveryStatus =>
      newDeliveryStatus.statusCode match {
        case MSG_DELIVERY_STATUS_FAILED.statusCode =>
          failedMsgs += newDeliveryStatus.uid -> deliveryStatus.values.map(_.failedAttemptCount).sum

          val criteria = retryEligibilityCriteriaProvider()

          if (isEligibleForRetries(criteria, msg, deliveryStatus)) {
            addAsEligibleForRetry(msgId)
          } else {
            removeAsEligibleForRetry(msgId)
          }
          if (isUndeliveredMsg(criteria, msg, deliveryStatus)) {
            undeliveredMsgs += msgId
          }

        case MSG_DELIVERY_STATUS_SENT.statusCode   =>
          failedMsgs -= newDeliveryStatus.uid
          eligibleForRetries -= newDeliveryStatus.uid
          undeliveredMsgs -= newDeliveryStatus.uid

        case _ => //nothing to do
      }
    }
  }

  private def addAsEligibleForRetry(msgId: MsgId): Unit = {
    removeAsEligibleForRetry(msgId)    //this is so that it gets appended to the end when added in below line
    eligibleForRetries += msgId
  }

  private def removeAsEligibleForRetry(msgId: MsgId): Unit = {
    eligibleForRetries -= msgId
  }

  private def filterRetriableDeliveryStatus(criteria: RetryEligibilityCriteria,
                                            msg: Msg,
                                            deliveryStatus: Map[String, MsgDeliveryDetail]): List[MsgDeliveryDetail] = {
    if (!criteria.exceptMsgTypes.contains(msg.getType) &&
      //only those msgs which is sent by given senderDID
      criteria.senderDID.forall(msg.senderDID == _)) {
      //only those msgs which has been failed in their previous delivery attempt
      // and their attempt count is not yet exceeded than max allowed retry count
        deliveryStatus
          .filter(ds => criteria.deliveryTargets.contains(ds._1) && ds._2.statusCode == MSG_DELIVERY_STATUS_FAILED.statusCode)
          .values
          .toList
    } else List.empty
  }

  private def isEligibleForRetries(criteria: RetryEligibilityCriteria,
                                   msg: Msg,
                                   deliveryStatus: Map[String, MsgDeliveryDetail]): Boolean = {
    filterRetriableDeliveryStatus(criteria, msg, deliveryStatus)
      .exists(_.failedAttemptCount < maxRetryAttempts)
  }

  private def isUndeliveredMsg(criteria: RetryEligibilityCriteria,
                               msg: Msg, deliveryStatus: Map[String, MsgDeliveryDetail]): Boolean = {
    filterRetriableDeliveryStatus(criteria, msg, deliveryStatus)
      .exists(_.failedAttemptCount >= maxRetryAttempts)
  }

  /**
   * returns total number of failed attempts for all failed msgs
   * @return
   */
  def getFailedAttemptCounts: Int = failedMsgs.values.sum

  /**
   * returns total number of failed messages (ignoring attempts)
   * @return
   */
  def getFailedMsgCounts: Int = failedMsgs.size

  /**
   * returns total undelivered messages (which won't be retried any more)
   * @return
   */
  def getUndeliveredMsgCounts: Int = undeliveredMsgs.size

  def getMsgsEligibleForRetry: Set[MsgId] = eligibleForRetries
}

/**
 * user to filter messages which should be retried
 * @param senderDID if provided, then only those failed messages are chosen which is sent by this DID
 * @param exceptMsgTypes if provided, then only those failed messages are chosen who's type is NOT one of the provided one
 * @param deliveryTargets if provided, then only those failed messages are chosen who's target was one of the provided one
 */
case class RetryEligibilityCriteria(senderDID: Option[DID]=None,
                                    exceptMsgTypes: Set[String]=Set.empty,
                                    deliveryTargets: Set[String]=Set.empty)