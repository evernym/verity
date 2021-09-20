package com.evernym.verity.msgoutbox

import java.util.concurrent.TimeUnit

import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef}
import akka.util.Timeout
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.agent.user.msgstore.MsgDetail
import com.evernym.verity.actor.typed.base.UserGuardian.defaultOutboxPassivationTimeoutInSeconds
import com.evernym.verity.config.ConfigConstants.PERSISTENT_ACTOR_BASE
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.msgoutbox.outbox.msg_packager.MsgPackagers
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.msg_transporter.{HttpTransporter, MsgTransports}
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.util2.RetentionPolicy

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

trait OutboxService {
  def sendMessage(
                   relRecipId: Map[RelId, RecipId],
                   msg: String,
                   msgType: String,
                   retentionPolicy: RetentionPolicy
                 ): Future[MsgId]

  def getMessages(
                   relId: RelId,
                   recipId: RecipId,
                   msgIds: List[MsgId],
                   statuses: List[String],
                   excludePayload: Boolean
                 ): Future[Seq[MsgDetail]]
}

class OutboxServiceImpl(val relResolver: RelResolver,
                        msgRepository: MessageRepository,
                        msgPackagers: MsgPackagers,
                        agentActorContext: AgentActorContext,
                        appConfig: AppConfig,
                        timeout: Option[Timeout] = None)
                       (implicit val ec: ExecutionContext, system: ActorSystem[Nothing]) extends OutboxService {

  val clusterSharding: ClusterSharding = ClusterSharding(system)
  val defaultOutboxPassivationTimeoutInSeconds: Int = 300
  clusterSharding.init(Entity(Outbox.TypeKey) { entityContext =>
    val msgTransports: MsgTransports = new MsgTransports {
      override val httpTransporter: Behavior[HttpTransporter.Cmd] = HttpTransporter.apply(agentActorContext.msgSendingSvc, ec)
    }
    Outbox(
      entityContext,
      appConfig.config,
      agentActorContext.oAuthAccessTokenRefreshers,
      relResolver,
      msgPackagers,
      msgTransports,
      ec,
      msgRepository
    )
  }.withSettings(
    ClusterShardingSettings(system)
      .withPassivateIdleEntityAfter(
        FiniteDuration(ConfigUtil.getReceiveTimeout(
          appConfig,
          defaultOutboxPassivationTimeoutInSeconds,
          PERSISTENT_ACTOR_BASE,
          Outbox.TypeKey.name,
          null,
        ).toSeconds, TimeUnit.SECONDS)
      )))

  implicit val tmt: Timeout = timeout.getOrElse(Timeout(5, TimeUnit.SECONDS))

  override def sendMessage(
                           relRecipId: Map[RelId, RecipId],
                           msg: String,
                           msgType: String,
                           retentionPolicy: RetentionPolicy
                         ): Future[MsgId] = {
    for {
      outboxIdParams <- Future.sequence(relRecipId.map(rr => relResolver.resolveOutboxParam(rr._1, rr._2)).toSet)
      msgId <- msgRepository.insert(msgType, msg, retentionPolicy, outboxIdParams)
      _ <- Future.sequence(outboxIdParams.map{param => outboxAddMessage(param, msgId, retentionPolicy)})
    } yield msgId
  }

  //FIRST IMPLEMENTATION OF GET_MSGS
  override def getMessages(
                           relId: RelId,
                           recipId: RecipId,
                           msgIds: List[MsgId],
                           statuses: List[String],
                           excludePayload: Boolean
                         ): Future[Seq[MsgDetail]] = {
    for {
      outboxIdParam <- relResolver.resolveOutboxParam(relId, recipId)
      Outbox.Replies.DeliveryStatus(messages) <- clusterSharding.entityRefFor(Outbox.TypeKey, outboxIdParam.entityId.toString)
            .askWithStatus(ref => Outbox.Commands.GetDeliveryStatus(msgIds, statuses, excludePayload, ref))
    } yield messages
  }

  private def outboxAddMessage(outboxIdParam: OutboxIdParam, msgId: MsgId, retentionPolicy: RetentionPolicy): Future[Unit] = {
    //for now we are not collecting AddMsg
    //we need to refactor interaction on Init message and collect replies from it as well (and make it askable)
    val outboxRef = clusterSharding.entityRefFor(Outbox.TypeKey, outboxIdParam.entityId.toString)
    for {
      outboxResponse <- outboxRef
        .ask(ref => Outbox.Commands.AddMsg(msgId, retentionPolicy.elements.expiryDuration, ref))
      resend <- outboxResponse match {
        case Outbox.Replies.NotInitialized(_) =>
          outboxRef.ask(ref => Outbox.Commands.Init(outboxIdParam.relId, outboxIdParam.recipId, outboxIdParam.destId, ref)).map(_ => true)
        case Outbox.Replies.MsgAdded | Outbox.Replies.MsgAlreadyAdded => Future.successful(false)
        case x => Future.failed(new RuntimeException(s"Message sending failed: unknown reply received: $x"))
      }
      resendResult <- if (resend) {
        outboxRef.ask(ref => Outbox.Commands.AddMsg(msgId, retentionPolicy.elements.expiryDuration, ref))
      } else {
        Future.successful(())
      }
      _ <- resendResult match {
        case Outbox.Replies.MsgAdded | Outbox.Replies.MsgAlreadyAdded | () => Future.successful(())
        case x => Future.failed(new RuntimeException(s"Message resending failed: unknown reply received: $x"))
      }
    } yield ()
  }
}

object OutboxService {
  def apply(relResolver: RelResolver,
            msgRepository: MessageRepository,
            msgPackagers: MsgPackagers,
            agentActorContext: AgentActorContext,
            appConfig: AppConfig,
            timeout: Option[Timeout] = None)
           (implicit ec: ExecutionContext, actorSystem: ActorSystem[Nothing]): OutboxService = {
    new OutboxServiceImpl(relResolver, msgRepository, msgPackagers, agentActorContext, appConfig, timeout)(ec, actorSystem)
  }
}
