package com.evernym.verity.actor.typed.base

import akka.{actor => classic}
import akka.actor.Props
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import com.evernym.verity.util2.RetentionPolicy
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.msgoutbox.message_meta.MessageMeta
import com.evernym.verity.msgoutbox.outbox.Outbox
import com.evernym.verity.msgoutbox.outbox.msg_store.MsgStore
import com.evernym.verity.msgoutbox.outbox.msg_packager.{Packagers, didcom_v1}
import com.evernym.verity.msgoutbox.outbox.msg_packager.didcom_v1.{DIDCommV1Packager, WalletOpExecutor}
import com.evernym.verity.msgoutbox.outbox.msg_transporter.{HttpTransporter, Transports}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.msgoutbox.router.OutboxRouter
import com.evernym.verity.config.AppConfig
import com.evernym.verity.metrics.{MetricsWriter, MetricsWriterExtension}
import com.evernym.verity.protocol.engine.ParticipantId


class Classic(agentActorContext: AgentActorContext)
  extends classic.Actor {

  def appConfig: AppConfig = agentActorContext.appConfig

  val sharding: ClusterSharding = ClusterSharding(context.system.toTyped)

  val metricsWriter: MetricsWriter = MetricsWriterExtension(context.system).get()

  val msgStore: ActorRef[MsgStore.Cmd] = {
    val blobStoreBucket: String = appConfig
      .config
      .getConfig("verity.blob-store")
      .getString("bucket-name")

    context.spawn(MsgStore(blobStoreBucket, agentActorContext.storageAPI), "msg-store")
  }

  val blobStoreBucket: String = appConfig
    .config
    .getConfig("verity.blob-store")
    .getString("bucket-name")

  val relResolver: Behavior[RelationshipResolver.Cmd] = RelationshipResolver(agentActorContext.agentMsgRouter)

  val packagers: Packagers = new Packagers {
    override val didCommV1Packager: Behavior[DIDCommV1Packager.Cmd] = {
      val walletOpExecutor: Behavior[WalletOpExecutor.Cmd] = didcom_v1.WalletOpExecutor(agentActorContext.walletAPI)
      DIDCommV1Packager(agentActorContext.agentMsgTransformer, walletOpExecutor, metricsWriter)
    }
  }
  val transports: Transports = new Transports {
    override val httpTransporter: Behavior[HttpTransporter.Cmd] = HttpTransporter.apply(agentActorContext.msgSendingSvc)
  }

  val messageMetaRegion: ActorRef[ShardingEnvelope[MessageMeta.Cmd]] =
    sharding.init(Entity(MessageMeta.TypeKey) { entityContext =>
      MessageMeta(
        entityContext,
        msgStore
      )
    })

  val outboxRegion: ActorRef[ShardingEnvelope[Outbox.Cmd]] =
    sharding.init(Entity(Outbox.TypeKey) { entityContext =>
      Outbox(
        entityContext,
        appConfig.config,
        relResolver,
        msgStore,
        packagers,
        transports
      )
    })

  override def receive: Receive = {
    case smto: SendMsgToOutbox =>
      val actorRef = context.spawnAnonymous(
        OutboxRouter(
          smto.fromParticipantId,
          smto.toParticipantId,
          smto.msg,
          smto.msgType,
          smto.retentionPolicy,
          relResolver,
          msgStore
        ))
      actorRef ! OutboxRouter.Commands.SendMsg
  }
}

case class SendMsgToOutbox(fromParticipantId: ParticipantId,
                           toParticipantId: ParticipantId,
                           msg: String,
                           msgType: String,
                           retentionPolicy: RetentionPolicy) extends ActorMessage

object Classic {
  def props(agentActorContext: AgentActorContext): Props = Props(new Classic(agentActorContext))
}