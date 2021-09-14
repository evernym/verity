package com.evernym.verity.msgoutbox

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.evernym.verity.msgoutbox.message_meta.MessageMeta.LegacyData
import com.evernym.verity.msgoutbox.message_repository.MessageRepositoryImpl
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.{ExecutionContext, Future}

trait MessageRepository {
  def insert(msgType: String, msg: String, retentionPolicy: RetentionPolicy, outboxParams: Set[OutboxIdParam]): Future[MsgId]
  def read(ids: List[MsgId], excludePayload: Boolean): Future[List[Msg]]
}

case class Msg(id: MsgId, `type`: String, legacyPayload: Option[LegacyData], payload: Option[Array[Byte]])

object MessageRepository {
  def apply(msgStore: ActorRef[MsgStore.Cmd], executionContext: ExecutionContext, actorSystem: ActorSystem[Nothing], timeout: Option[Timeout] = None): MessageRepository = {
    new MessageRepositoryImpl(msgStore, timeout)(executionContext, actorSystem)
  }
}