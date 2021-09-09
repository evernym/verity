package com.evernym.verity.msgoutbox.message_repository

import java.util.UUID

import akka.actor.ActorContext
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.{IMessageRepository, MsgId}
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class MessageRepository(val msgStore: ActorRef[MsgStore.Cmd])(implicit val executionContext: ExecutionContext, actorContext: ActorContext) extends IMessageRepository {
  val system: ActorSystem[_] = actorContext.system.toTyped
  val clusterSharding: ClusterSharding = ClusterSharding(system)
  implicit val timeout: Timeout = ???
  implicit val scheduler: Scheduler = ???

  override def createMessage(msgType: String, msg: String, retentionPolicy: RetentionPolicy, outboxParams: Set[OutboxIdParam]): Future[MsgId] = {
    val msgId = UUID.randomUUID().toString
    val messageMetaRef = clusterSharding.entityRefFor(MessageMeta.TypeKey, msgId)
    for {
      _ <- msgStore.ask(ref => MsgStore.Commands.StorePayload(msgId, msg.getBytes, retentionPolicy, ref))
      _ <- messageMetaRef.ask(ref => MessageMeta.Commands.Add(msgType, retentionPolicy.configString, outboxParams.map(_.entityId.toString), None, None, ref))
    } yield msgId
  }

  override def getMessage(id: MsgId, deliveryStatus: String, excludePayload: Boolean): Future[Try[MsgDetail]] = {
    val messageMetaRef = clusterSharding.entityRefFor(MessageMeta.TypeKey, id)
    for {
      reply <- messageMetaRef.ask(ref => MessageMeta.Commands.Get(ref))
    } yield reply match {
      case MessageMeta.Replies.MsgNotYetAdded =>
        Failure(new Exception("Message not found"))
      case MessageMeta.Replies.Msg(id, typ, legacy, payload) =>
        Success(MsgDetail(
          id,
          typ,
          legacy.map(_.senderDID).get,
          deliveryStatus,
          legacy.flatMap(_.refMsgId),
          None, // it is None for now, Rajesh will clarify the need for the thread
          if (excludePayload) None else payload,
          Set()
        ))
    }
  }
}