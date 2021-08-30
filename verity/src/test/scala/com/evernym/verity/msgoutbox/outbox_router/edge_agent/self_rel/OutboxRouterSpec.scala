package com.evernym.verity.msgoutbox.outbox_router.edge_agent.self_rel

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import com.evernym.verity.actor.agent.relationship.{AuthorizedKey, AuthorizedKeys, DidDoc, EndpointADT, Endpoints, HttpEndpoint, Relationship}
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.SELF_RELATIONSHIP
import com.evernym.verity.msgoutbox.{DestId, RelId}
import com.evernym.verity.msgoutbox.base.DestParam
import com.evernym.verity.msgoutbox.outbox_router.edge_agent.{EdgeAgentOutboxRouterBaseSpec, VerityEdgeAgent}
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Commands.{GetRelParam, SendOutboxParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Replies.{OutboxParam, RelParam}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext


class OutboxRouterSpec
  extends EdgeAgentOutboxRouterBaseSpec {

  "OutboxRouter" - {
    "when received a request to send an outgoing signal message to my domain" - {
      "should process it successfully" in {
        val ack = sendToOutboxRouter(
          VerityEdgeAgent.selfRelContext.selfParticipantId,
          VerityEdgeAgent.selfRelContext.myDomainParticipantId,
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
  private val logger = getLoggerByClass(getClass)

  def apply(destParams: Map[DestId, DestParam]): Behavior[RelationshipResolver.Cmd] = {
    Behaviors.setup { actorContext =>
      initialized(destParams)(actorContext)
    }
  }

  def initialized(destParams: Map[DestId, DestParam])
                 (implicit actorContext: ActorContext[RelationshipResolver.Cmd]): Behavior[RelationshipResolver.Cmd] = {
    Behaviors.receiveMessage[RelationshipResolver.Cmd] {
      case SendOutboxParam(relId, destId, replyTo: ActorRef[RelationshipResolver.OutboxReply]) =>
        destParams.get(destId).foreach { destParam =>
          replyTo ! OutboxParam(destParam.walletId, destParam.myVerKey, destParam.comMethods)
        }
        Behaviors.same

      case GetRelParam(relId: RelId, replyTo: ActorRef[RelationshipResolver.Reply]) =>
          replyTo ! RelParam(
            VerityEdgeAgent.selfRelContext.myDID,
            Option(
              Relationship(
                SELF_RELATIONSHIP,
                "self-relationship",
                Option(
                  DidDoc(
                    VerityEdgeAgent.selfRelContext.myDID,
                    Option(
                      AuthorizedKeys(
                        Seq(
                          AuthorizedKey(VerityEdgeAgent.selfRelContext.myDID, "selfRelDIDKey", Set.empty)
                        ),
                      )
                    ),
                    Option(
                      Endpoints(
                        Seq(
                          HttpEndpoint(
                            "webhook",
                            "http://www.webhook.com",
                            Seq.empty,
                            None, None
                          )
                        ).map(EndpointADT(_))
                      )
                    )
                  )
                ),
                Seq.empty
              )
            )
          )
        Behaviors.same

      case cmd =>
        logger.warn(s"Received unexpected command ${cmd}")
        Behaviors.same
    }
  }
}