package com.evernym.verity.msgoutbox

import akka.actor.ActorContext
import akka.actor.typed.ActorRef
import akka.util.Timeout
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.msgoutbox.message_repository.MessageRepositoryImpl
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.{ExecutionContext, Future}

trait MessageRepository {
  def insert(msgType: String, msg: String, retentionPolicy: RetentionPolicy, outboxParams: Set[OutboxIdParam]): Future[MsgId]
  def read(id: MsgId, deliveryStatus: String, excludePayload: Boolean): Future[MsgDetail]
}

object MessageRepository {
  def apply(msgStore: ActorRef[MsgStore.Cmd], executionContext: ExecutionContext, actorContext: ActorContext, timeout: Option[Timeout] = None): MessageRepository = {
    new MessageRepositoryImpl(msgStore, timeout)(executionContext, actorContext)
  }
}