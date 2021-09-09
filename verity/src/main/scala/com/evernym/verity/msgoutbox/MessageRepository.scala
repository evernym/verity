package com.evernym.verity.msgoutbox

import akka.actor.ActorContext
import akka.actor.typed.ActorRef
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.msgoutbox.message_repository.MessageRepository
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait IMessageRepository {
  def createMessage(msgType: String, msg: String, retentionPolicy: RetentionPolicy, outboxParams: Set[OutboxIdParam]): Future[MsgId]
  def getMessage(id: MsgId, deliveryStatus: String, excludePayload: Boolean): Future[Try[MsgDetail]]
}

object MessageRepository {
  def apply(msgStore: ActorRef[MsgStore.Cmd], executionContext: ExecutionContext, actorContext: ActorContext): IMessageRepository = {
    new MessageRepository(msgStore)(executionContext, actorContext)
  }
}