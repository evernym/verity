package com.evernym.verity.msgoutbox.outbox.msg_store

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.msgoutbox.MsgId
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore.Commands.{DeletePayload, GetPayload, StorePayload}
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore.Replies.{PayloadDeleted, PayloadRetrieved, PayloadStored}
import com.evernym.verity.storage_services.{BucketLifeCycleUtil, StorageAPI}
import com.evernym.verity.util2.RetentionPolicy
import org.slf4j.event.Level

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


object MsgStore {

  sealed trait Cmd extends ActorMessage

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

  trait Reply extends ActorMessage
  sealed trait GetPayloadReply extends Reply

  object Replies {
    object PayloadStored extends Reply
    object PayloadDeleted extends GetPayloadReply
    case class PayloadRetrieved(payload: Option[Array[Byte]]) extends Reply
  }

  def apply(bucketName: String, storageAPI: StorageAPI, executionContext: ExecutionContext): Behavior[Cmd] = {
    Behaviors.setup { actorContext =>
      Behaviors
        .supervise(initialized(bucketName, storageAPI, executionContext)(actorContext)) //TODO: finalize this
        .onFailure[RuntimeException]( //TODO: finalize this
          SupervisorStrategy
            .restart
            .withLogLevel(Level.INFO)
            .withLoggingEnabled(enabled = true)
            .withLimit(maxNrOfRetries = 10, withinTimeRange = 10.seconds)
        )
    }
  }

  private def initialized(bucketName: String,
                          storageAPI: StorageAPI,
                          executionContext: ExecutionContext)
                         (implicit actorContext: ActorContext[Cmd]): Behavior[Cmd] = Behaviors.receiveMessage[Cmd] {

    case StorePayload(msgId, payload, policy, replyTo) =>
      val msgLifecycleAddress = BucketLifeCycleUtil.lifeCycleAddress(
        Option(policy.elements.expiryDaysStr), msgId)
      storageAPI.put(bucketName, msgLifecycleAddress, payload).foreach { _ =>
        replyTo ! PayloadStored
      }(executionContext)
      Behaviors.same

    case GetPayload(msgId, policy, replyTo) =>
      val msgLifecycleAddress = BucketLifeCycleUtil.lifeCycleAddress(
        Option(policy.elements.expiryDaysStr), msgId)
      storageAPI.get(bucketName, msgLifecycleAddress).foreach { p =>
        replyTo ! PayloadRetrieved(p)
      }(executionContext)
      Behaviors.same

    case DeletePayload(msgId, policy, replyTo) =>
      val msgLifecycleAddress = BucketLifeCycleUtil.lifeCycleAddress(
        Option(policy.elements.expiryDaysStr), msgId)
      storageAPI.delete(bucketName, msgLifecycleAddress).foreach { _ =>
        replyTo ! PayloadDeleted
      }(executionContext)
      Behaviors.same
  }
}
