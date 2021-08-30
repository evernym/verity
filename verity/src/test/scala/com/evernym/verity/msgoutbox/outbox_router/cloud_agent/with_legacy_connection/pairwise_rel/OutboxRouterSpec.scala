package com.evernym.verity.msgoutbox.outbox_router.cloud_agent.with_legacy_connection.pairwise_rel

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import com.evernym.verity.actor.agent.relationship.{AuthorizedKey, AuthorizedKeys, DidDoc, EndpointADT, Endpoints, Relationship, RoutingServiceEndpoint}
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.msgoutbox.{DestId, RelId}
import com.evernym.verity.msgoutbox.base.DestParam
import com.evernym.verity.msgoutbox.outbox_router.cloud_agent.with_legacy_connection.{CloudAgentOutboxRouterBaseSpec, VerityCloudAgent}
import com.evernym.verity.msgoutbox.outbox_router.edge_agent.VerityEdgeAgent
import com.evernym.verity.msgoutbox.outbox.{Outbox, OutboxIdParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Commands.{GetRelParam, SendOutboxParam}
import com.evernym.verity.msgoutbox.rel_resolver.RelationshipResolver.Replies.{OutboxParam, RelParam}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
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

    "when received a request to send an incoming protocol message to my domain" - {
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

    "when received a request to send an outgoing protocol message to other domain" - {
      "should process it successfully" in {
        val ack = sendToOutboxRouter(
          VerityCloudAgent.pairwiseRelContext.myPairwiseDID,
          VerityCloudAgent.pairwiseRelContext.theirPairwiseDID,
          "msg",
          "msg-type"
        )
        checkOutboxProcessing(ack, Seq(OutboxIdParam("myPairwiseDID", "theirPairwiseDID", "default").entityId.toString))
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
                        AuthorizedKey(VerityCloudAgent.pairwiseRelContext.myPairwiseDID, "myPairwiseDID", Set.empty),
                        AuthorizedKey(VerityCloudAgent.pairwiseRelContext.thisAgentKeyId, s"thisAgentKeyId", Set.empty),
                      )
                    )
                  )
                )
              ),
              Seq(
                DidDoc(
                  VerityEdgeAgent.pairwiseRelContext.theirPairwiseDID,
                  Option(
                    AuthorizedKeys(
                      Seq(AuthorizedKey(VerityEdgeAgent.pairwiseRelContext.theirPairwiseDID, "theirPairwiseDIDKey", Set.empty))
                    )
                  ),
                  Option(
                    Endpoints(
                      Seq(
                        RoutingServiceEndpoint(
                          "http://their.endpoint.com",
                          Seq("their-route-key-1"),
                          Seq.empty
                        )
                      ).map(EndpointADT(_))
                    )
                  )
                )
              )
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