package com.evernym.verity.drivers

import akka.pattern.ask
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.agent.{SetupAgentEndpoint, SetupCreateKeyEndpoint}
import com.evernym.verity.protocol.actor._
import com.evernym.verity.protocol.engine.{DID, PinstId, ProtoRef, SignalEnvelope}
import com.evernym.verity.protocol.legacy.services.{CreateAgentEndpointDetail, CreateKeyEndpointDetail}
import com.evernym.verity.protocol.protocols.agentprovisioning.common.{AgentCreationCompleted, AskUserAgentCreator}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_5._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.util.Util._
import com.evernym.verity.util.ParticipantUtil

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import scala.concurrent.Future
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{IdentifySponsor, ProvisioningNeeded}


class AgentProvisioningDriver(cp: ActorDriverGenParam)
  extends ActorDriver(cp) {

  override def signal[A]: SignalHandler[A] = {
    case SignalEnvelope(apc: AskAgencyPairwiseCreator, protoRef, pinstId, _, _)    =>
      handleCreatePairwiseKey(apc, protoRef, pinstId)

    case SignalEnvelope(apc: AskUserAgentCreator, protoRef, pinstId, _, _)         =>
      handleCreateAgent(apc, protoRef, pinstId)

    case se @ SignalEnvelope(_: ProvisioningNeeded, _, _, _, _)                    =>
      processSignalMsg(se)

    case se @ SignalEnvelope(_: IdentifySponsor, _, _, _, _)                       =>
      processSignalMsg(se)
  }

  def handleCreatePairwiseKey(apc: AskAgencyPairwiseCreator, protoRef: ProtoRef, pinstId: PinstId): Option[Control] = {

    def sendPairwiseCreated(respFut: Future[Any], agentKeyDID: DID, protoRef: ProtoRef, pinstId: PinstId): Unit = {
      //TODO this is ignoring the response... What if it's an error?
      respFut.foreach( _ =>
        sendToProto(protoRef, pinstId, PairwiseEndpointCreated(
          ParticipantUtil.participantId(agentKeyDID, None)))
      )
    }

    val endpointDetail = DefaultMsgCodec.fromJson[CreateKeyEndpointDetail](apc.endpointDetailJson)
    val newActorEntityId = getNewActorId
    val protocolDetail = ProtocolIdDetail(protoRef, pinstId)
    val cmd = SetupCreateKeyEndpoint(apc.newAgentKeyDID, apc.theirPairwiseDID,
      endpointDetail.ownerDID, endpointDetail.ownerAgentKeyDID,
      endpointDetail.ownerAgentActorEntityId, Option(protocolDetail))
    val respFut = agencyPairwiseRegion ? ForIdentifier(newActorEntityId, cmd)
    sendPairwiseCreated(respFut, apc.newAgentKeyDID, protoRef, pinstId)
    None
  }

  def handleCreateAgent(apc: AskUserAgentCreator, protoRef: ProtoRef, pinstId: PinstId): Option[Control] = {

    def sendAgentCreated(respFut: Future[Any], protoRef: ProtoRef, pinstId: PinstId): Unit = {
      respFut.foreach(_ => sendToProto(protoRef, pinstId, AgentCreationCompleted()))
    }

    val endpointSetupDetail = DefaultMsgCodec.fromJson[CreateAgentEndpointDetail](apc.endpointDetailJson)

    val cmd = SetupAgentEndpoint(apc.forDID, apc.agentKeyDID)
    val respFut = userRegion ? ForIdentifier(endpointSetupDetail.entityId, cmd)
    sendAgentCreated(respFut, protoRef, pinstId)
    None
  }

}
