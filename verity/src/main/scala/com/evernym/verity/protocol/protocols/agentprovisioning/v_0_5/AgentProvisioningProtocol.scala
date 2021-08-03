package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_5

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, InvalidValueException}
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.AgentDetail
import com.evernym.verity.actor.wallet.{AgentWalletSetupCompleted, GetVerKeyOptResp, GetVerKeyResp, NewKeyCreated, TheirKeyStored}
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.did.{DidStr, DidPair, VerKeyStr}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.container.actor.{Init, ProtoMsg}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.agentprovisioning.common.{AgentCreationCompleted, AskUserAgentCreator, HasAgentProvWallet}
import com.evernym.verity.util.{Base58Util, ParticipantUtil}
import com.typesafe.scalalogging.Logger

import scala.util.{Failure, Success}


sealed trait Role
object Requester extends Role
object Initiater extends Role    //TODO: fix the role name
object Provisioner extends Role

trait AgentProvisioningEvt

class AgentProvisioningProtocol(val ctx: ProtocolContextApi[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String])
    extends Protocol[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String](AgentProvisioningProtoDef)
      with HasLogger
      with HasAgentProvWallet {

  val logger: Logger = ctx.logger

  lazy val appConfig: AppConfig = ctx.SERVICES_DEPRECATED.appConfig

  override def applyEvent: ApplyEvent = {

    case (_, _, pi: ProtocolInitialized) =>
      val parameters = Parameters(pi.parameters.map(p => Parameter(p.name, p.value)).toSet)
      (State.Initialized(parameters), initialize(pi.parameters))

    case (oa: State.Initialized, _, RequesterPartiSet(id)) =>
      val roster = ctx.getRoster.withParticipant(id)
      (State.RequesterPartiIdSet(oa.parameters), roster.withAssignment(
        Requester -> roster.participantIndex(id).get      //TODO: fix .get
      ))

    case (oa: State.RequesterPartiIdSet, _, ProvisioningInitiaterPartiSet(partiId)) =>
      val roster = ctx.getRoster.withParticipant(partiId)
      (State.ProvisioningInitiaterPartiIdSet(oa.parameters), roster.withAssignment(
        Initiater -> roster.participantIndex(partiId).get   //TODO: fix .get
      ))

    case (cr: State.ProvisioningInitiaterPartiIdSet, _, PairwiseDIDSet(fromDID, pairwiseDID)) =>
      State.PairwiseDIDSet(cr.parameters, AgentDetail(fromDID, pairwiseDID))

    case (rps: State.PairwiseDIDSet, _, PairwiseEndpointSet(partiId)) =>
      val roster = ctx.getRoster.withParticipant(partiId, isSelf = true)
      (State.Connected(rps.parameters, rps.pdd), roster.withAssignment(
        Provisioner -> roster.selfIndex_!
      ))

    case (c: State.Connected, _, SignedUp()) =>
      State.Signedup(c.parameters, c.pdd)

    case (_: State.Signedup, _, AgentPairwiseKeyCreated(did: DidStr, verKey: VerKeyStr)) =>
      State.AgentKeyCreated(did, verKey)

    case (_: State.AgentKeyCreated, _, UserAgentCreated()) =>
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
    case (oa: State.Initialized, _, crm: ConnectReqMsg_MFV_0_5)
                        => handleConnectMsg(crm, oa.parameters)

    case (_: State.Connected, _, _: ConnectReqMsg_MFV_0_5)
                        => throw new BadRequestErrorException(CONN_STATUS_ALREADY_CONNECTED.statusCode)

    case (_: State.Connected, _, _: CreateAgentReqMsg_MFV_0_5)
                        => throw new BadRequestErrorException(NOT_REGISTERED.statusCode)

    case (_: State.Connected, _, _: SignUpReqMsg_MFV_0_5)
                        => handleSignupMsg()

    case (_: State.Signedup, _, _: SignUpReqMsg_MFV_0_5)
                        => throw new BadRequestErrorException(ALREADY_REGISTERED.statusCode)

    case (s:State.Signedup, _, ca: CreateAgentReqMsg_MFV_0_5)
                        => handleCreateAgentMsg(s)

    case (_:State.AgentCreated, _, _: CreateAgentReqMsg_MFV_0_5)
                        => throw new BadRequestErrorException(AGENT_ALREADY_CREATED.statusCode)
  }

  override def handleControl: Control ?=> Any = {
    case c: Control => mainHandleControl(ctx.getState, c)
  }

  protected def mainHandleControl: (State, Control) ?=> Unit = {
    case (_: State.Uninitialized , ip: Init)                        => handleInitParams(ip)
    case (pkc: State.PairwiseDIDSet, pec: PairwiseEndpointCreated)  => handlePairwiseEndpointCreated(pec, pkc.pdd)
    case (apkc: State.AgentKeyCreated, _: AgentCreationCompleted)   => handleAgentCreated(apkc)
  }

  private def handleInitParams(ip: Init): Unit = {
    val parameters = ip.params.initParams.map(p => ParameterStored(p.name, p.value))
    ctx.apply(ProtocolInitialized(parameters.toSeq))
  }

  private def handleConnectMsg(crm: ConnectReqMsg_MFV_0_5, initParameters: Parameters): Unit = {
    if (ConfigUtil.sponsorRequired(appConfig)) throw new BadRequestErrorException(PROVISIONING_PROTOCOL_DEPRECATED.statusCode)
    validateConnectMsg(crm) {
      processValidatedConnectMsg(crm, initParameters)
    }
  }

  private def validateConnectMsg(crm: ConnectReqMsg_MFV_0_5)(postValidation: => Unit): Unit = {
    checkIfDIDBelongsToVerKey(crm.fromDID, crm.fromDIDVerKey)
    ctx.wallet.verKeyOpt(crm.fromDID) {
      case Success(GetVerKeyOptResp(None))     => postValidation
      case Success(GetVerKeyOptResp(Some(_)))  => throw new BadRequestErrorException(CONN_STATUS_ALREADY_CONNECTED.statusCode)
      case Failure(e)                          => throw e
    }
  }

  private def checkIfDIDBelongsToVerKey(did: DidStr, verKey: VerKeyStr): Unit = {
    val verifKey = Base58Util.decode(verKey).get
    val didFromVerKey = Base58Util.encode(verifKey.take(16))
    if (did != didFromVerKey) {
      throw new InvalidValueException(Option(s"DID and verKey not belonging to each other (DID: $did, verKey: $verKey)"))
    }
  }

  private def processValidatedConnectMsg(crm: ConnectReqMsg_MFV_0_5, initParameters: Parameters): Unit = {
    ctx.wallet.newDid() {
      case Success(nkc: NewKeyCreated)  =>
        ctx.wallet.storeTheirDid(crm.fromDID, crm.fromDIDVerKey) {
          case Success(_: TheirKeyStored) =>
            ctx.apply(RequesterPartiSet(ParticipantUtil.participantId(crm.fromDID, None)))
            val provisionerPartiId = initParameters.paramValueRequired(AGENT_PROVISIONER_PARTICIPANT_ID)
            ctx.apply(ProvisioningInitiaterPartiSet(provisionerPartiId))
            ctx.apply(PairwiseDIDSet(crm.fromDID, nkc.did))
            val endpointDetail = initParameters.paramValueRequired(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON)
            val askPairwiseCreator = AskAgencyPairwiseCreator(
              nkc.didPair,
              crm.didPair,
              endpointDetail
            )
            ctx.signal(askPairwiseCreator)
          case Failure(e) => throw e
        }
      case Failure(e)     => throw e
    }
  }

  private def handlePairwiseEndpointCreated(pec: PairwiseEndpointCreated, pd: AgentDetail): Unit = {
    ctx.wallet.verKey(pd.agentKeyDID) {
      case Success(gvkr: GetVerKeyResp) =>
        ctx.apply(PairwiseEndpointSet(pec.participantId))
        val connectedMsg = ConnectedRespMsg_MFV_0_5(pd.agentKeyDID, gvkr.verKey)
        ctx.send(connectedMsg, toRole=Option(Requester), fromRole=Option(Initiater))
      case Failure(e) => throw e
    }
  }

  private def handleSignupMsg(): Unit = {
    ctx.apply(SignedUp())
    val signedUpRespMsg = SignedUpRespMsg_MFV_0_5()
    ctx.send(signedUpRespMsg, toRole = Option(Requester), fromRole = Option(Provisioner))
  }

  private def handleCreateAgentMsg(s:State.Signedup): Unit = {
    if (ConfigUtil.sponsorRequired(appConfig)) throw new BadRequestErrorException(PROVISIONING_PROTOCOL_DEPRECATED.statusCode)
    ctx.wallet.verKey(s.pdd.forDID) {
      case Success(gvkr: GetVerKeyResp) =>
        val fromDIDPair = DidPair(s.pdd.forDID, gvkr.verKey)
        val aws = s.parameters.paramValueRequired(NEW_AGENT_WALLET_ID)
        prepareNewAgentWalletData(fromDIDPair, aws) {
          case Success(awsc: AgentWalletSetupCompleted) =>
            ctx.apply(AgentPairwiseKeyCreated(awsc.agentKey.did, awsc.agentKey.verKey))
            val endpointDetail = s.parameters.paramValueRequired(CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON)
            ctx.signal(AskUserAgentCreator(fromDIDPair, awsc.agentKey.didPair, endpointDetail))
          case Failure(e) => throw e
        }
      case Failure(e) => throw e
    }
  }

  private def handleAgentCreated(akc: State.AgentKeyCreated): Unit = {
    val agentCreatedRespMsg = AgentCreatedRespMsg_MFV_0_5(akc.did, akc.verKey)
    ctx.apply(UserAgentCreated())
    ctx.send(agentCreatedRespMsg, toRole = Option(Requester), fromRole = Option(Provisioner))
  }
}

/**
  * Signal
  */
case class AskAgencyPairwiseCreator(newAgentKeyDIDPair: DidPair, theirPairwiseDIDPair: DidPair, endpointDetailJson: String)

case class PairwiseEndpointCreated(participantId: ParticipantId) extends Control with ActorMessage
