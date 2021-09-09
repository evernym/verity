package com.evernym.verity.msgoutbox

import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.Future

trait IMessageRepository {
  def createMessage(msgId: MsgId, msgType: String, msg: String, retentionPolicy: RetentionPolicy, outboxParams: Set[OutboxIdParam]): Future[Unit]
  def getMessage(): Future[MsgDetail]
}

object MessageRepository {
  def apply(): IMessageRepository {
    new MessageRepository()
  }
}