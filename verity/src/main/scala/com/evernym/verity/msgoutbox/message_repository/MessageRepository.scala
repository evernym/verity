package com.evernym.verity.msgoutbox.message_repository

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.AskPattern._
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.{IMessageRepository, MsgId}
import com.evernym.verity.util2

import scala.concurrent.{ExecutionContext, Future}

class MessageRepository(val msgStore: ActorRef[MsgStore.Cmd])(implicit val executionContext: ExecutionContext) extends IMessageRepository {
  override def storeMessage(msgId: MsgId, msg: String, retentionPolicy: util2.RetentionPolicy): Future[Unit] = {
    for {
      _ <- msgStore.ask(ref => MsgStore.Commands.StorePayload(msgId, msg.getBytes, retentionPolicy, ref))
    } yield {}
  }
}