package com.evernym.verity.drivers

import akka.pattern.ask
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.agent.{SetupAgentEndpoint, SetupCreateKeyEndpoint}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.did.DidStr
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.container.actor._
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.{PinstId, ProtoRef, SignalEnvelope}
import com.evernym.verity.protocol.legacy.services.{CreateAgentEndpointDetail, CreateKeyEndpointDetail}
import com.evernym.verity.protocol.protocols.agentprovisioning.common.{AgentCreationCompleted, AskUserAgentCreator}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_5._
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{IdentifySponsor, ProvisioningNeeded}
import com.evernym.verity.util.ParticipantUtil
import com.evernym.verity.util.Util._

import scala.concurrent.{ExecutionContext, Future}


class AgentProvisioningDriver(cp: ActorDriverGenParam, ec: ExecutionContext)
  extends ActorDriver(cp, ec) {

  private implicit def executionContext: ExecutionContext = ec

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

    def sendPairwiseCreated(respFut: Future[Any], agentKeyDID: DidStr, protoRef: ProtoRef, pinstId: PinstId): Unit = {
      //TODO this is ignoring the response... What if it's an error?
      respFut.foreach { _ =>
        sendToProto(
          protoRef,
          pinstId,
          ProtocolCmd(
            PairwiseEndpointCreated(ParticipantUtil.participantId(agentKeyDID, None)),
            None
          )
        )
      }
    }

    val endpointDetail = DefaultMsgCodec.fromJson[CreateKeyEndpointDetail](apc.endpointDetailJson)
    val newActorEntityId = getNewActorId
    val protocolDetail = ProtocolIdDetail(protoRef, pinstId)
    val cmd = SetupCreateKeyEndpoint(
      apc.newAgentKeyDIDPair.toAgentDidPair,
      apc.theirPairwiseDIDPair.toAgentDidPair,
      endpointDetail.ownerDID,
      endpointDetail.ownerAgentKeyDidPair.map(_.toAgentDidPair),
      endpointDetail.ownerAgentActorEntityId,
      Option(protocolDetail)
    )
    val respFut = agencyPairwiseRegion ? ForIdentifier(newActorEntityId, cmd)
    sendPairwiseCreated(respFut, apc.newAgentKeyDIDPair.did, protoRef, pinstId)
    None
  }

  def handleCreateAgent(apc: AskUserAgentCreator, protoRef: ProtoRef, pinstId: PinstId): Option[Control] = {

    def sendAgentCreated(respFut: Future[Any], protoRef: ProtoRef, pinstId: PinstId): Unit = {
      respFut.foreach { _ =>
        sendToProto(
          protoRef,
          pinstId,
          ProtocolCmd(AgentCreationCompleted(), None)
        )
      }
    }

    val endpointSetupDetail = DefaultMsgCodec.fromJson[CreateAgentEndpointDetail](apc.endpointDetailJson)

    val cmd = SetupAgentEndpoint(apc.forDIDPair.toAgentDidPair, apc.agentKeyDIDPair.toAgentDidPair)
    val respFut = userRegion ? ForIdentifier(endpointSetupDetail.entityId, cmd)
    sendAgentCreated(respFut, protoRef, pinstId)
    None
  }

}
