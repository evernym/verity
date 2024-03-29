package com.evernym.verity.msgoutbox.message_repository

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.outbox.OutboxIdParam
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.{MessageRepository, Msg, MsgId}
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.{ExecutionContext, Future}

class MessageRepositoryImpl(val msgStore: ActorRef[MsgStore.Cmd], timeout: Option[Timeout] = None)(implicit val executionContext: ExecutionContext, actorSystem: ActorSystem[Nothing]) extends MessageRepository {
  val clusterSharding: ClusterSharding = ClusterSharding(actorSystem)
  implicit val tmt: Timeout = timeout.getOrElse(Timeout(5, TimeUnit.SECONDS))

  override def insert(msgType: String, msg: String, retentionPolicy: RetentionPolicy, outboxParams: Set[OutboxIdParam]): Future[MsgId] = {
    val msgId = UUID.randomUUID().toString
    val messageMetaRef = clusterSharding.entityRefFor(MessageMeta.TypeKey, msgId)
    for {
      _ <- msgStore.ask(ref => MsgStore.Commands.StorePayload(msgId, msg.getBytes, retentionPolicy, ref))
      _ <- messageMetaRef.ask(ref => MessageMeta.Commands.Add(msgType, retentionPolicy.configString, outboxParams.map(_.entityId.toString), None, None, ref))
    } yield msgId
  }

  override def read(ids: List[MsgId], excludePayload: Boolean): Future[List[Msg]] = {
    Future.sequence( ids.map( id =>
      for {
        MessageMeta.Replies.Msg(id, typ, legacy, retentionPolicy, recipPackaging, _) <- clusterSharding
          .entityRefFor(MessageMeta.TypeKey, id)
          .ask(ref => MessageMeta.Commands.Get(ref))
        payload <- if (excludePayload) Future.successful(None) else msgStore
          .ask(ref => MsgStore.Commands.GetPayload(id, retentionPolicy, ref))
          .map(res => res.payload)
      } yield Msg(id, typ, legacy, payload, retentionPolicy, recipPackaging)
    ))
  }

}