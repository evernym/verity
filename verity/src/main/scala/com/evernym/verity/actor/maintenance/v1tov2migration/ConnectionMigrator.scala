package com.evernym.verity.actor.maintenance.v1tov2migration

import akka.actor.Props
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.{GetDomainDetail, GetDomainDetailResp}
import com.evernym.verity.actor.{ActorMessage, AgentDetailSet, ConnectionStatusUpdated, OwnerSetForAgent, RouteSet, TheirDidDocDetail}
import com.evernym.verity.actor.base.{CoreActorExtended, Done}
import com.evernym.verity.actor.wallet.{StoreTheirKey, TheirKeyStored}
import com.evernym.verity.config.{AppConfig, ConfigConstants}
import com.evernym.verity.constants.ActorNameConstants
import com.evernym.verity.constants.InitParamConstants.{DATA_RETENTION_POLICY, LOGO_URL, NAME}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.MultiEvent
import com.evernym.verity.protocol.engine.events.{DataRetentionPolicySet, DomainIdSet, LegacyPackagingContextSet, PackagingContextSet, StorageIdSet}
import com.evernym.verity.protocol.protocols.protocolRegistry
import com.evernym.verity.protocol.protocols.relationship.v_1_0.{CreatingPairwiseKey, InitParam, Initialized, PairwiseKeyCreated, RelationshipDef}
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.WalletAPI

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}


class ConnectionMigrator(appConfig: AppConfig,
                         walletAPI: WalletAPI,
                         agentMsgRouter: AgentMsgRouter)
                        (implicit val futureExecutionContext: ExecutionContext)
  extends CoreActorExtended {

  override def receiveCmd: Receive = {
    case smc: SetupMigratedConnection => handleSetupMigratedConnection(smc)
  }

  private def handleSetupMigratedConnection(smc: SetupMigratedConnection): Unit = {
    logger.info(s"[$actorId] new pairwise connection setup started...")
    val sndr = sender()
    agentMsgRouter
      .execute(InternalMsgRouteParam(smc.agent.agentDID, GetDomainDetail))
      .mapTo[GetDomainDetailResp]
      .flatMap { dd =>
        Future.sequence(
          Seq(
            setupRelationshipProtocolState(smc.connection.myDidDoc, dd),
            setupUserAgentPairwiseState(smc),
            setupAgentWalletState(dd.walletId, smc)
          )
        )
      }.map { _ =>
        sndr ! Done
        logger.info(s"[$actorId] new pairwise connection setup finished")
      }
  }

  private def setupAgentWalletState(walletId: String, smc: SetupMigratedConnection): Future[Any] = {
    val fut1 = storeTheirKey(walletId, smc.connection.theirDidDoc.agencyDID, smc.connection.theirDidDoc.agencyDIDVerKey)
    val fut2 = storeTheirKey(walletId, smc.connection.theirDidDoc.pairwiseDID, smc.connection.theirDidDoc.pairwiseDIDVerKey)
    Future.sequence(Seq(fut1, fut2))
  }

  private def setupUserAgentPairwiseState(smc: SetupMigratedConnection): Future[Any] = {

    Future {
      //TODO: finalize this
      val pairwiseAgentActorEntityId =
        UUID
          .nameUUIDFromBytes((smc.agent.agentDID + smc.connection.myDidDoc.pairwiseDID).getBytes())
          .toString
      logger.info(s"[$actorId] pairwiseAgentActorEntityId: " + pairwiseAgentActorEntityId)

      //set route for new pairwise DID
      context.system.actorOf(
        EventPersister.props(
          appConfig,
          futureExecutionContext,
          ActorNameConstants.ROUTE_REGION_ACTOR_NAME,
          smc.connection.myDidDoc.pairwiseDID,
          Option(appConfig.getStringReq(ConfigConstants.SECRET_ROUTING_AGENT)),
          Seq(RouteSet(6, pairwiseAgentActorEntityId))
        )
      )

      //set new pairwise agent actor
      context.system.actorOf(
        EventPersister.props(
          appConfig,
          futureExecutionContext,
          ActorNameConstants.USER_AGENT_PAIRWISE_REGION_ACTOR_NAME,
          pairwiseAgentActorEntityId,
          None,
          Seq(
            OwnerSetForAgent(
              smc.agent.agentDID,
              smc.agent.agentDID,
              smc.agent.agentDidVerKey
            ),
            AgentDetailSet(
              smc.connection.myDidDoc.pairwiseDID,
              smc.connection.myDidDoc.pairwiseDID,
              smc.connection.myDidDoc.pairwiseDIDVerKey,
              smc.connection.myDidDoc.pairwiseDIDVerKey
            ),
            ConnectionStatusUpdated(
              reqReceived = true,
              answerStatusCode = smc.connection.answerStatusCode,
              Option(
                TheirDidDocDetail(
                  smc.connection.theirDidDoc.pairwiseDID,
                  smc.connection.theirDidDoc.agencyDID,
                  smc.connection.theirDidDoc.pairwiseAgentDID,
                  smc.connection.theirDidDoc.pairwiseAgentVerKey,
                  "", //no need for agent key delegation proof
                  smc.connection.theirDidDoc.pairwiseDIDVerKey
                )
              ),
              None
            )
          )
        )
      )
    }
  }

  //TODO: fix TODOs mentioned in below method
  private def setupRelationshipProtocolState(myDidDoc: MyPairwiseDidDoc,
                                             dd: GetDomainDetailResp): Future[Any] = {
    Future {
      protocolRegistry
        .find(RelationshipDef.protoRef)
        .map { entry =>

          //TODO: is this OK?
          val threadId = UUID.nameUUIDFromBytes(myDidDoc.pairwiseDID.getBytes()).toString

          val pinstId = entry.pinstIdResol.resolve(
            RelationshipDef,
            dd.domainId,
            dd.relationshipId,
            Option(threadId),
            None,
            None
          )
          val dataRetentionPolicy =
            dd
              .relationshipProtocolParams
              .find(_.name == DATA_RETENTION_POLICY)
              .map(_.value)
              .getOrElse(throw new RuntimeException("data retention policy not found"))

          val inviteeName =
            dd
              .relationshipProtocolParams
              .find(_.name == NAME)
              .map(_.value)
              .getOrElse(throw new RuntimeException("name not found"))

          val inviteeLogoUrl =
            dd
              .relationshipProtocolParams
              .find(_.name == LOGO_URL)
              .map(_.value)
              .getOrElse(throw new RuntimeException("logo url not found"))

          context.system.actorOf(
            EventPersister.props(
              appConfig,
              futureExecutionContext,
              "relationship-1.0-protocol",
              pinstId,
              None,
              Seq(
                DomainIdSet(dd.domainId),
                StorageIdSet(pinstId),
                DataRetentionPolicySet(dataRetentionPolicy),
                Initialized(dd.relationshipProtocolParams.map(p => InitParam(p.name, p.value)).toSeq),
                MultiEvent(Seq(PackagingContextSet(2), LegacyPackagingContextSet(2))),
                CreatingPairwiseKey(inviteeName, inviteeLogoUrl),
                PairwiseKeyCreated(inviteeName, myDidDoc.pairwiseDID, myDidDoc.pairwiseDIDVerKey, inviteeLogoUrl)
              )
            )
          )
        }
    }
  }

  private def storeTheirKey(walletId: String, theirDID: DidStr, theirDIDVerKey: VerKeyStr): Future[TheirKeyStored] = {
    implicit val wap: WalletAPIParam = WalletAPIParam(walletId)
    walletAPI.executeAsync[TheirKeyStored](
      StoreTheirKey(theirDID, theirDIDVerKey, ignoreIfAlreadyExists = true)
    )
  }

  private val logger = getLoggerByClass(getClass)
}

object ConnectionMigrator {
  def props(appConfig: AppConfig,
            walletAPI: WalletAPI,
            agentMsgRouter: AgentMsgRouter,
            futureExecutionContext: ExecutionContext): Props =
    Props(new ConnectionMigrator(appConfig, walletAPI, agentMsgRouter)(futureExecutionContext))
}

case class SetupMigratedConnection(agent: Agent,
                                   connection: Connection) extends ActorMessage
case class Agent(agentDID: DidStr, agentDidVerKey: VerKeyStr)
case class Connection(answerStatusCode: String,
                      myDidDoc: MyPairwiseDidDoc,
                      theirDidDoc: TheirPairwiseDidDoc)
case class MyPairwiseDidDoc(pairwiseDID: DidStr, pairwiseDIDVerKey: VerKeyStr)
case class TheirPairwiseDidDoc(pairwiseDID: DidStr,
                               pairwiseDIDVerKey: VerKeyStr,
                               pairwiseAgentDID: DidStr,
                               pairwiseAgentVerKey: VerKeyStr,
                               agencyDID: DidStr,
                               agencyDIDVerKey: VerKeyStr)