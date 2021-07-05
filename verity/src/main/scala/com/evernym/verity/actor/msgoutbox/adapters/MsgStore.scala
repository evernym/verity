package com.evernym.verity.actor.msgoutbox.adapters

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.RetentionPolicy
import com.evernym.verity.actor.msgoutbox.adapters.MsgStore.Commands.{DeletePayload, GetPayload, StorePayload}
import com.evernym.verity.actor.msgoutbox.adapters.MsgStore.Replies.{PayloadDeleted, PayloadRetrieved, PayloadStored}
import com.evernym.verity.actor.msgoutbox.outbox.MsgId
import com.evernym.verity.storage_services.{BucketLifeCycleUtil, StorageAPI}
import org.slf4j.event.Level

import scala.concurrent.duration.DurationInt


object MsgStore {

  sealed trait Cmd
  object Commands {
    case class StorePayload(msgId: MsgId,
                            payload: Array[Byte],
                            policy: RetentionPolicy,
                            replyTo: ActorRef[Replies.PayloadStored.type]) extends Cmd

    case class GetPayload(msgId: MsgId,
                          policy: RetentionPolicy,
                          replyTo: ActorRef[Replies.PayloadRetrieved]) extends Cmd

    case class DeletePayload(msgId: MsgId,
                             policy: RetentionPolicy,
                             replyTo: ActorRef[Replies.PayloadDeleted.type]) extends Cmd
  }

  trait Reply
  object Replies {
    object PayloadStored extends Reply
    object PayloadDeleted extends Reply
    case class PayloadRetrieved(payload: Option[Array[Byte]]) extends Reply
  }

  def apply(bucketName: String, storageAPI: StorageAPI): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      Behaviors
        .supervise(initialized(bucketName, storageAPI)(actorContext)) //TODO: finalize this
        .onFailure[Throwable](
          SupervisorStrategy
            .restart
            .withLogLevel(Level.INFO)
            .withLoggingEnabled(enabled = true)
            .withLimit(maxNrOfRetries = 10, withinTimeRange = 10.seconds)

        )
    }
  }

  private def initialized(bucketName: String,
                          storageAPI: StorageAPI)
                         (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {

    case StorePayload(msgId, payload, policy, replyTo) =>
      val msgLifecycleAddress = BucketLifeCycleUtil.lifeCycleAddress(
        Option(policy.elements.expiryDaysStr), msgId)
      storageAPI.put(bucketName, msgLifecycleAddress, payload).foreach { _ => replyTo ! PayloadStored }
      Behaviors.same

    case GetPayload(msgId, policy, replyTo) =>
      val msgLifecycleAddress = BucketLifeCycleUtil.lifeCycleAddress(
        Option(policy.elements.expiryDaysStr), msgId)
      storageAPI.get(bucketName, msgLifecycleAddress).foreach { p =>
        replyTo ! PayloadRetrieved(p)
      }
      Behaviors.same

    case DeletePayload(msgId, policy, replyTo) =>
      val msgLifecycleAddress = BucketLifeCycleUtil.lifeCycleAddress(
        Option(policy.elements.expiryDaysStr), msgId)
      storageAPI.delete(bucketName, msgLifecycleAddress).foreach { d => replyTo ! PayloadDeleted }
      Behaviors.same
  }
}
