package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_6

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status.{AGENT_ALREADY_CREATED, PROVISIONING_PROTOCOL_DEPRECATED}
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.wallet.{NewKeyCreated, TheirKeyStored}
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.container.actor.{Init, ProtoMsg}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.agentprovisioning.common.{AgentCreationCompleted, AskUserAgentCreator, HasAgentProvWallet}
import com.evernym.verity.util.ParticipantUtil
import com.typesafe.scalalogging.Logger

import scala.util.{Failure, Success}

sealed trait Role
object Requester extends Role
object Provisioner extends Role

trait AgentProvisioningEvt

class AgentProvisioningProtocol(val ctx: ProtocolContextApi[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String])
    extends Protocol[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String](AgentProvisioningProtoDef)
      with HasLogger
      with HasAgentProvWallet {

  val logger: Logger = ctx.logger

  override def applyEvent: ApplyEvent = {

    case (_, _, pi: ProtocolInitialized) =>
      val parameters = Parameters(pi.parameters.map(p => Parameter(p.name, p.value)).toSet)
      (State.Initialized(parameters), initialize(pi.parameters))

    case (_: State.Initialized, _, RequesterPartiSet(id)) =>
      val roster = ctx.getRoster.withParticipant(id)
      (State.RequesterPartiIdSet(), roster.withAssignment(
        Requester -> roster.participantIndex(id).get
      ))

    case (_: State.RequesterPartiIdSet, _, ProvisionerPartiSet(id)) =>
      val roster = ctx.getRoster.withParticipant(id)
      (State.ProvisionerPartiIdSet(), roster.withAssignment(
        Provisioner -> roster.participantIndex(id).get
      ))

    case (_: State.ProvisionerPartiIdSet, _, AgentPairwiseKeyCreated(did, verKey)) =>
      State.AgentPairwiseKeyCreated(did, verKey)

    case (_: State.AgentPairwiseKeyCreated, _, UserAgentCreated()) =>
      State.AgentCreated()
  }

  def initialize(params: Seq[ParameterStored]): Roster[Role] = {
    //TODO: this still feels like boiler plate, need to come back and fix it
    //NOTE: this updatedParams is done to be able to populate 'selfId' in the roster
    // which is required to be able to use ctx.wallet apis
    val updatedParams = params.map { p =>
      if (p.name == AGENT_PROVISIONER_PARTICIPANT_ID) {
        ParameterStored(SELF_ID, p.value)
      } else p
    }
    ctx.updatedRoster(updatedParams.map(p => InitParamBase(p.name, p.value)))
  }

  override def handleProtoMsg: (State, Option[Role], ProtoMsg) ?=> Any = {
    case (oa: State.Initialized, _, ca: CreateAgentReqMsg_MFV_0_6) => handleCreateAgentMsg(ca, oa)
    case (_:State.AgentCreated, _, _: CreateAgentReqMsg_MFV_0_6)   => throw new BadRequestErrorException(AGENT_ALREADY_CREATED.statusCode)
  }

  override def handleControl: Control ?=> Any = {
    case c: Control => mainHandleControl(ctx.getState, c)
  }

  protected def mainHandleControl: (State, Control) ?=> Unit = {
    case (_: State.Uninitialized , ip: Init)                              => handleInitParams(ip)
    case (apkc: State.AgentPairwiseKeyCreated, _: AgentCreationCompleted) => handleAgentCreated(apkc)
  }

  private def handleInitParams(ip: Init): Unit = {
    ctx.apply(ProtocolInitialized(ip.parametersStored.toSeq))
  }

  private def handleCreateAgentMsg(ca: CreateAgentReqMsg_MFV_0_6, oa: State.Initialized): Unit = {
    if (ConfigUtil.sponsorRequired(appConfig)) throw new BadRequestErrorException(PROVISIONING_PROTOCOL_DEPRECATED.statusCode)
    val fromDID = ca.fromDID
    val fromDIDVerKey = ca.fromDIDVerKey
    val fromDIDPair = DidPair(fromDID, fromDIDVerKey)
    val aws = oa.parameters.paramValueRequired(NEW_AGENT_WALLET_ID)
    prepareNewAgentWalletData(fromDIDPair, aws) {
      case Success(nkc: NewKeyCreated) =>
        ctx.wallet.storeTheirDid(fromDID, fromDIDVerKey, ignoreIfAlreadyExists = true) {
          case Success(_: TheirKeyStored) =>
            ctx.apply(RequesterPartiSet(ParticipantUtil.participantId(nkc.did, Option(fromDID)))) //TODO: confirm if this is correct
            val provisionerPartiId = oa.parameters.paramValueRequired(AGENT_PROVISIONER_PARTICIPANT_ID)
            ctx.apply(ProvisionerPartiSet(provisionerPartiId))
            ctx.apply(AgentPairwiseKeyCreated(nkc.did, nkc.verKey))
            val endpointDetail = oa.parameters.paramValueRequired(CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON)
            ctx.signal(AskUserAgentCreator(fromDIDPair, nkc.didPair, endpointDetail))
          case Failure(e) => throw e
        }
      case Failure(e) => throw e
    }
  }

  private def handleAgentCreated(apkc: State.AgentPairwiseKeyCreated): Unit = {
    val agentCreatedRespMsg = AgentCreatedRespMsg_MFV_0_6(apkc.did, apkc.verKey)
    ctx.apply(UserAgentCreated())
    ctx.send(agentCreatedRespMsg, toRole = Option(Requester), fromRole = Option(Provisioner))
  }

  def appConfig: AppConfig = ctx.SERVICES_DEPRECATED.appConfig
}
