package com.evernym.verity.actor.maintenance.v1tov2migration

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.{GetDomainDetail, GetDomainDetailResp}
import com.evernym.verity.actor.{ActorMessage, AgentDetailSet, ConnectionStatusUpdated, ForIdentifier, OwnerSetForAgent, RouteSet, TheirDidDocDetail}
import com.evernym.verity.actor.base.{CoreActorExtended, Done, Stop}
import com.evernym.verity.actor.wallet.{StoreTheirKey, TheirKeyStored}
import com.evernym.verity.config.{AppConfig, ConfigConstants}
import com.evernym.verity.constants.ActorNameConstants
import com.evernym.verity.constants.ActorNameConstants.ROUTE_REGION_ACTOR_NAME
import com.evernym.verity.constants.InitParamConstants.{DATA_RETENTION_POLICY, LOGO_URL, NAME}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.MultiEvent
import com.evernym.verity.protocol.engine.events.{DataRetentionPolicySet, DomainIdSet, LegacyPackagingContextSet, PackagingContextSet, StorageIdSet}
import com.evernym.verity.protocol.protocols.protocolRegistry
import com.evernym.verity.protocol.protocols.relationship.v_1_0.{CreatingPairwiseKey, InitParam, Initialized, PairwiseKeyCreated, RelationshipDef}
import com.evernym.verity.util2.Status
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vault.wallet_api.WalletAPI

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


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
        //below logic is to make sure it deterministically calculates the same
        // pairwise actor entity id for given 'VAS agent' + 'Enterprise pairwise DID' combination
        // to avoid creating lots of stale actors if migration script is
        // executed multiple times (for testing purposes, dry run etc)
        val pairwiseAgentActorEntityId =
          UUID
            .nameUUIDFromBytes((smc.agent.agentDID + smc.connection.myDidDoc.pairwiseDID).getBytes())
            .toString
        Future.sequence(
          Seq(
            setupAgentWalletState(dd.walletId, smc),
            setupRelationshipProtocolState(smc.connection.myDidDoc, dd),
            setupUserAgentPairwiseState(smc, pairwiseAgentActorEntityId).flatMap { _ =>
              stopRouteActor(smc.connection.myDidDoc.pairwiseDID)
            }
          )
        )
      }.map { _ =>
        sndr ! Done
        logger.info(s"[$actorId] new pairwise connection setup finished")
      }
  }

  private def setupAgentWalletState(walletId: String, smc: SetupMigratedConnection): Future[Any] = {
    if (smc.connection.theirDidDoc.pairwiseDID != "") {
      val fut1 = storeTheirKey(walletId, smc.connection.theirDidDoc.pairwiseDID, smc.connection.theirDidDoc.pairwiseDIDVerKey)
      val fut2 = storeTheirKey(walletId, smc.connection.theirDidDoc.agencyDID, smc.connection.theirDidDoc.agencyDIDVerKey)
      Future.sequence(Seq(fut1, fut2))
    } else Future.successful(Done)
  }

  //this is to make sure when next time it is restarted, it reads the events persisted
  // as part of 'setupUserAgentPairwiseState'
  private def stopRouteActor(did: DidStr): Future[Any] = {
    implicit val timeout: Timeout = Timeout(15.seconds)
    routeRegion ? ForIdentifier(did, Stop(sendAck = true))
  }

  private def setupUserAgentPairwiseState(smc: SetupMigratedConnection,
                                          pairwiseAgentActorEntityId: String): Future[Any] = {
    Future {
      logger.debug(s"[$actorId] pairwiseAgentActorEntityId: " + pairwiseAgentActorEntityId)

      //set route for new pairwise DID
      context.system.actorOf(
        EventPersister.props(
          futureExecutionContext,
          appConfig,
          ActorNameConstants.ROUTE_REGION_ACTOR_NAME,
          smc.connection.myDidDoc.pairwiseDID,
          Option(appConfig.getStringReq(ConfigConstants.SECRET_ROUTING_AGENT)),
          Seq(RouteSet(6, pairwiseAgentActorEntityId))
        )
      )

      //set new pairwise agent actor
      context.system.actorOf(
        EventPersister.props(
          futureExecutionContext,
          appConfig,
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
            if (smc.connection.answerStatusCode == Status.MSG_STATUS_ACCEPTED.statusCode) {
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
                )
              )
            } else {
              ConnectionStatusUpdated(
                reqReceived = true,
                answerStatusCode = smc.connection.answerStatusCode,
                None,
                None
              )
            }
          )
        )
      )
    }
  }

  //relationship protocol is supposed to be long living protocol
  // and hence setting it up as well to avoid any issues in future
  // when any new functionality is added to relationship protocol
  // and expected to work for all existing relationships.
  private def setupRelationshipProtocolState(myDidDoc: MyPairwiseDidDoc,
                                             dd: GetDomainDetailResp): Future[Any] = {
    Future {
      protocolRegistry
        .find(RelationshipDef.protoRef)
        .map { entry =>

          //using pairwise DID as seed to calculate threadId deterministically
          // in case it has to be used by the customer later on
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
              futureExecutionContext,
              appConfig,
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

  val routeRegion: ActorRef = ClusterSharding(context.system).shardRegion(ROUTE_REGION_ACTOR_NAME)

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
                               agencyEndpoint: String,
                               agencyDID: DidStr,
                               agencyDIDVerKey: VerKeyStr)