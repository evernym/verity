package com.evernym.verity.protocol.container.actor

import akka.pattern.ask
import akka.cluster.sharding.ClusterSharding
import akka.util.Timeout
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.actor.{ActorMessage, AddDetail, ForIdentifier, ForToken}
import com.evernym.verity.actor.agent.{SetupAgentEndpoint, SetupCreateKeyEndpoint}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.ConfigConstants.TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS
import com.evernym.verity.constants.ActorNameConstants.TOKEN_TO_ACTOR_ITEM_MAPPER_REGION_ACTOR_NAME
import com.evernym.verity.constants.Constants.DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS
import com.evernym.verity.did.DidPair
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.container.ProtocolContainer
import com.evernym.verity.protocol.engine.util.getNewActorIdFromSeed
import com.evernym.verity.protocol.engine.{PinstId, ProtoRef}
import com.evernym.verity.protocol.legacy.services.{AgentEndpointServiceProvider, CreateAgentEndpointDetail, CreateKeyEndpointDetail, CreateKeyEndpointServiceProvider, LegacyProtocolServicesImpl, TokenToActorMappingProvider}
import com.evernym.verity.util.TokenProvider
import com.evernym.verity.util.Util.buildDuration
import com.evernym.verity.util2.Status
import com.github.ghik.silencer.silent

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
 * this trait contains support for legacy protocols (connecting 0.5/0.6 and agent provisioning 0.5/0.6)
 *
 * @tparam M Message type
 * @tparam E Event type
 * @tparam I Message Recipient Identifier Type
 */
trait HasLegacyProtocolContainerServices[M,E,I]
extends TokenToActorMappingProvider
  with MsgQueueServiceProvider
  with CreateKeyEndpointServiceProvider
  with AgentEndpointServiceProvider
  with HasExecutionContextProvider {
    this: ActorProtocolContainer[_,_,_,M,E,_,I] with ProtocolContainer[_,_,M,E,_,I] =>

  @silent
  override def createServices: Option[Services] = {

    Some(new LegacyProtocolServicesImpl[M,E,I](
      agentActorContext.appConfig,
      agentActorContext.walletAPI, agentActorContext.generalCache,
      agentActorContext.msgSendingSvc, agentActorContext.agentMsgTransformer, publishAppStateEvent,
      this, this, this))
  }

  def addToMsgQueue(msg: Any): Unit = {
    self ! ProtocolCmd(msg, None)
  }

  /*
  We call this function when we want to create a pairwise actor. It creates a key
  and the pairwise actor as well. The pairwise actor is effectively an "endpoint", since
  it is where you will receive messages from the other side.
   */
  def setupCreateKeyEndpoint(forDIDPair: DidPair, agentKeyDIDPair: DidPair, endpointDetailJson: String): Future[Any] = {
    val endpointDetail = DefaultMsgCodec.fromJson[CreateKeyEndpointDetail](endpointDetailJson)
    val cmd = SetupCreateKeyEndpoint(
      agentKeyDIDPair.toAgentDidPair,
      forDIDPair.toAgentDidPair,
      endpointDetail.ownerDID,
      endpointDetail.ownerAgentKeyDidPair.map(_.toAgentDidPair),
      endpointDetail.ownerAgentActorEntityId,
      Option(getProtocolIdDetail)
    )
    sendCmdToRegionActor(endpointDetail.regionTypeName, newEndpointActorEntityId, cmd)
  }

  def setupNewAgentEndpoint(forDIDPair: DidPair, agentKeyDIDPair: DidPair, endpointDetailJson: String): Future[Any] = {
    val endpointSetupDetail = DefaultMsgCodec.fromJson[CreateAgentEndpointDetail](endpointDetailJson)
    sendCmdToRegionActor(
      endpointSetupDetail.regionTypeName,
      endpointSetupDetail.entityId,
      SetupAgentEndpoint(
        forDIDPair.toAgentDidPair,
        agentKeyDIDPair.toAgentDidPair
      )
    )
  }

  //NOTE: this method is used to compute entity id of the new pairwise actor this protocol will use
  // in 'setupCreateKeyEndpoint' method. The reason behind using 'entityId' of this protocol actor as a seed,
  // so that, in later stage (once endpoint has created), if this protocol actor needs (like for agent provisioning etc)
  // to reach out to same pairwise actor, then, it can use below function to compute same entity id which was
  // created during 'setupCreateKeyEndpoint'.
  //TODO: We may wanna come back to this and find better solution.
  def newEndpointActorEntityId: String = {
    getNewActorIdFromSeed(entityId)
  }

  def getProtocolIdDetail: ProtocolIdDetail = ProtocolIdDetail(getProtoRef, entityId)

  // For each sharded actor, there will be one region actor per type per node. The region
  // actor manages all the shard actors. See https://docs.google.com/drawings/d/1vyjsGYjEQtvQbwWVFditnTXP-JyhIIrMc2FATy4-GVs/edit
  def sendCmdToRegionActor(regionTypeName: String, toEntityId: String, cmd: Any): Future[Any] = {
    val regionActorRef = ClusterSharding.get(context.system).shardRegion(regionTypeName)
    regionActorRef ? ForIdentifier(toEntityId, cmd)
  }

  override def createToken(uid: String): Future[Either[HandledErrorException, String]] = {
    val duration: FiniteDuration =
      buildDuration(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)
    implicit lazy val responseTimeout: Timeout = Timeout(duration)
    val tokenToActorItemMapperRegion = ClusterSharding(agentActorContext.system).shardRegion(TOKEN_TO_ACTOR_ITEM_MAPPER_REGION_ACTOR_NAME)
    val token = TokenProvider.getNewToken
    val fut = tokenToActorItemMapperRegion ? ForToken(token, AddDetail(entityType, entityId, uid))
    fut.map { _ =>
      Right(token)
    }
  }
}

/**
 * This is used to update delivery status of the message.
 * Currently it is used by Connecting protocol and UserAgentPairwise both
 * @param uid - unique message id
 * @param to - delivery destination (phone no, remote agent DID, edge DID etc)
 * @param statusCode - new status code
 * @param statusDetail - status detail
 */
case class UpdateMsgDeliveryStatus(uid: MsgId, to: String, statusCode: String,
                                   statusDetail: Option[String]) extends Control with ActorMessage {

  def isFailed: Boolean = statusCode == Status.MSG_DELIVERY_STATUS_FAILED.statusCode
}

/**
 * Purpose of this service is to provide a way for protocol to schedule a message for itself
 */
trait MsgQueueServiceProvider {
  def addToMsgQueue(msg: Any): Unit
}

case class ProtocolIdDetail(protoRef: ProtoRef, pinstId: PinstId)
