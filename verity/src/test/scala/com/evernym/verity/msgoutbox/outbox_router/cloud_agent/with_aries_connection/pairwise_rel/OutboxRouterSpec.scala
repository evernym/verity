package com.evernym.verity.msgoutbox.outbox_router.cloud_agent.with_aries_connection.pairwise_rel

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import com.evernym.verity.actor.agent.relationship.{AuthorizedKey, AuthorizedKeys, DidDoc, Relationship}
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.msgoutbox.{DestId, RelId}
import com.evernym.verity.msgoutbox.base.DestParam
import com.evernym.verity.msgoutbox.outbox_router.cloud_agent.with_aries_connection.{CloudAgentOutboxRouterBaseSpec, VerityCloudAgent}
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Commands.{GetRelParam, SendOutboxParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Replies.{OutboxParam, RelParam}
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext

class OutboxRouterSpec
  extends CloudAgentOutboxRouterBaseSpec {

  "OutboxRouter" - {

    "when received a request to send an outgoing signal message to my domain" - {
      "should process it successfully" in {
        val ack = sendToOutboxRouter(
          VerityCloudAgent.pairwiseRelContext.selfParticipantId,
          VerityCloudAgent.pairwiseRelContext.myDomainParticipantId,
          "msg",
          "msg-type"
        )
        checkOutboxProcessing(ack, Seq(OutboxIdParam("selfRelDID","selfRelDID", "default").entityId.toString))
      }
    }

    "when received a request to send an outgoing protocol message to my domain" - {
      "should process it successfully" in {
        val ack = sendToOutboxRouter(
          VerityCloudAgent.pairwiseRelContext.myPairwiseDID,
          VerityCloudAgent.pairwiseRelContext.myDomainParticipantId,
          "msg",
          "msg-type"
        )
        checkOutboxProcessing(ack, Seq(OutboxIdParam("selfRelDID","selfRelDID", "default").entityId.toString))
      }
    }
  }

  override val testRelResolver: Behavior[RelationshipResolver.Cmd] =
    TestAgentRelResolver(Map("default" -> DestParam(testWallet.walletId, myKey1.verKey, defaultDestComMethods)))

  val outboxRegion: ActorRef[ShardingEnvelope[Outbox.Cmd]] =
    sharding.init(Entity(Outbox.TypeKey) { entityContext =>
      Outbox(
        entityContext,
        appConfig.withFallback(OVERRIDE_CONFIG).config,
        testAccessTokenRefreshers,
        testRelResolver,
        testMsgStore,
        testMsgPackagers,
        testMsgTransports,
        executionContext
      )
    })

  lazy val ecp = new ExecutionContextProvider(appConfig)
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}

object TestAgentRelResolver {
  def apply(destParams: Map[DestId, DestParam]): Behavior[RelationshipResolver.Cmd] = {
    Behaviors.setup { actorContext =>
      initialized(destParams)(actorContext)
    }
  }

  def initialized(destParams: Map[DestId, DestParam])
                 (implicit actorContext: ActorContext[RelationshipResolver.Cmd]): Behavior[RelationshipResolver.Cmd] = {
    Behaviors.receiveMessage[RelationshipResolver.Cmd] {
      case SendOutboxParam(relId, destId, replyTo: ActorRef[RelationshipResolver.Reply]) =>
        destParams.get(destId).foreach { destParam =>
          replyTo ! OutboxParam(destParam.walletId, destParam.myVerKey, destParam.comMethods)
        }
        Behaviors.same

      case GetRelParam(relId: RelId, replyTo: ActorRef[RelationshipResolver.Reply]) =>
        replyTo ! RelParam(
          VerityCloudAgent.selfRelContext.myDID,
          Option(
            Relationship(
              PAIRWISE_RELATIONSHIP,
              "pairwise-relationship",
              Option(
                DidDoc(
                  VerityCloudAgent.pairwiseRelContext.myPairwiseDID,
                  Option(
                    AuthorizedKeys(
                      Seq(
                        AuthorizedKey(VerityCloudAgent.pairwiseRelContext.myPairwiseDID, "myPairwiseDIDKey", Set.empty),
                        AuthorizedKey(VerityCloudAgent.pairwiseRelContext.thisAgentKeyId, s"thisAgentKey", Set.empty),
                      )
                    )
                  )
                )
              ),
              Seq.empty
            )
          )
        )
        Behaviors.same
    }
  }
}