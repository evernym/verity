package com.evernym.verity.protocol.container.actor

import akka.pattern.ask
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.Status
import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, TokenToActorItemMapperProvider}
import com.evernym.verity.actor.agent.{DidPair, SetupAgentEndpoint, SetupCreateKeyEndpoint}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.util.getNewActorIdFromSeed
import com.evernym.verity.protocol.engine.{MsgId, PinstId, ProtoRef, ProtocolContainer}
import com.evernym.verity.protocol.legacy.services.{AgentEndpointServiceProvider, CreateAgentEndpointDetail, CreateKeyEndpointDetail, CreateKeyEndpointServiceProvider, LegacyProtocolServicesImpl, TokenToActorMappingProvider}
import com.github.ghik.silencer.silent

import scala.concurrent.Future

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
  with AgentEndpointServiceProvider {
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
    val cmd = SetupCreateKeyEndpoint(agentKeyDIDPair, forDIDPair, endpointDetail.ownerDID,
      endpointDetail.ownerAgentKeyDidPair, endpointDetail.ownerAgentActorEntityId, Option(getProtocolIdDetail))
    sendCmdToRegionActor(endpointDetail.regionTypeName, newEndpointActorEntityId, cmd)
  }

  def setupNewAgentEndpoint(forDIDPair: DidPair, agentKeyDIDPair: DidPair, endpointDetailJson: String): Future[Any] = {
    val endpointSetupDetail = DefaultMsgCodec.fromJson[CreateAgentEndpointDetail](endpointDetailJson)
    sendCmdToRegionActor(endpointSetupDetail.regionTypeName, endpointSetupDetail.entityId,
      SetupAgentEndpoint(forDIDPair, agentKeyDIDPair))
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
    TokenToActorItemMapperProvider.createToken(entityType, entityId, uid)(appConfig, agentActorContext.system)
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