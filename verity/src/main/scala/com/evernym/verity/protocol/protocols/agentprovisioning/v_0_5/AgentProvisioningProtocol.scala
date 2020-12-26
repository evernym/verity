package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_5

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.Exceptions.{BadRequestErrorException, InvalidValueException}
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.AgentDetail
import com.evernym.verity.actor.wallet.StoreTheirKey
import com.evernym.verity.cache.Cache
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.actor.{Init, ProtoMsg}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.legacy.services.DEPRECATED_HasWallet
import com.evernym.verity.protocol.protocols.agentprovisioning.common.{AgentCreationCompleted, AgentWalletSetupProvider, AskUserAgentCreator}
import com.evernym.verity.util.{Base58Util, ParticipantUtil}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.typesafe.scalalogging.Logger


sealed trait Role
object Requester extends Role
object Initiater extends Role    //TODO: fix the role name
object Provisioner extends Role

trait AgentProvisioningEvt

class AgentProvisioningProtocol(val ctx: ProtocolContextApi[AgentProvisioningProtocol, Role, ProtoMsg, Any, State, String])
    extends Protocol[AgentProvisioningProtocol,Role,ProtoMsg,Any,
      State, String](AgentProvisioningProtoDef)
      with HasLogger
      with AgentWalletSetupProvider
      with DEPRECATED_HasWallet {

  val logger: Logger = ctx.logger

  override lazy val appConfig: AppConfig = ctx.SERVICES_DEPRECATED.appConfig
  lazy val generalCache: Cache = ctx.SERVICES_DEPRECATED.generalCache

  override def applyEvent: ApplyEvent = {

    case (_, _, pi: ProtocolInitialized) =>
      initState(pi.parameters)
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

    case (_: State.Signedup, _, AgentPairwiseKeyCreated(did: DID, verKey: VerKey)) =>
      State.AgentKeyCreated(did, verKey)

    case (_: State.AgentKeyCreated, _, UserAgentCreated()) =>
      State.AgentCreated()

  }

  private def initState(params: Seq[ParameterStored]): Unit = {
    val seed = params.find(_.name == THIS_AGENT_WALLET_ID).get.value
    initWalletDetail(seed)
  }

  def initialize(params: Seq[ParameterStored]): Roster[Role] = {
    //TODO: this still feels like boiler plate, need to come back and fix it
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
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

    case (_: State.Uninitialized , ip: Init)                              => handleInitParams(ip)

    case (pkc: State.PairwiseDIDSet, pec: PairwiseEndpointCreated)        => handlePairwiseEndpointCreated(pec, pkc.pdd)

    case (apkc: State.AgentKeyCreated, _: AgentCreationCompleted) => handleAgentCreated(apkc)

  }

  private def handleInitParams(ip: Init): Unit = {
    val parameters = ip.params.initParams.map(p => ParameterStored(p.name, p.value))
    ctx.apply(ProtocolInitialized(parameters.toSeq))
  }

  private def handleConnectMsg(crm: ConnectReqMsg_MFV_0_5, initParameters: Parameters): Unit = {
    if (ConfigUtil.sponsorRequired(appConfig)) throw new BadRequestErrorException(PROVISIONING_PROTOCOL_DEPRECATED.statusCode)
    validateConnectMsg(crm)
    processValidatedConnectMsg(crm, initParameters)
  }

  private def validateConnectMsg(crm: ConnectReqMsg_MFV_0_5): Unit = {
    checkIfDIDBelongsToVerKey(crm.fromDID, crm.fromDIDVerKey)
    checkIfDIDAlreadyExists(crm.fromDID)
  }

  private def checkIfDIDBelongsToVerKey(did: DID, verKey: VerKey): Unit = {
    val verifKey = Base58Util.decode(verKey).get
    val didFromVerKey = Base58Util.encode(verifKey.take(16))
    if (did != didFromVerKey) {
      throw new InvalidValueException(Option(s"DID and verKey not belonging to each other (DID: $did, verKey: $verKey)"))
    }
  }

  /**
   * ensures agent provisioning throws error if it is already provisioned
   * @param forDID
   */
  def checkIfDIDAlreadyExists(forDID: DID): Unit = {
    if (getVerKeyViaCache(forDID).isDefined) {
      throw new BadRequestErrorException(CONN_STATUS_ALREADY_CONNECTED.statusCode)
    }
  }

  private def processValidatedConnectMsg(crm: ConnectReqMsg_MFV_0_5, initParameters: Parameters): Unit = {
    val agentPairwiseKey = walletAPI.createNewKey()
    storeTheirKey(crm.fromDID, crm.fromDIDVerKey)
    ctx.apply(RequesterPartiSet(ParticipantUtil.participantId(crm.fromDID, None)))
    val provisionerPartiId = initParameters.paramValueRequired(AGENT_PROVISIONER_PARTICIPANT_ID)
    ctx.apply(ProvisioningInitiaterPartiSet(provisionerPartiId))
    val event = PairwiseDIDSet(crm.fromDID, agentPairwiseKey.did)
    ctx.apply(event)
    val endpointDetail = initParameters.paramValueRequired(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON)
    val askPairwiseCreator = AskAgencyPairwiseCreator(agentPairwiseKey.did, crm.fromDID, endpointDetail)
    ctx.signal(askPairwiseCreator)
  }

  private def handlePairwiseEndpointCreated(pec: PairwiseEndpointCreated, pd: AgentDetail): Unit = {
    val pvk = getVerKeyReqViaCache(pd.agentKeyDID)
    ctx.apply(PairwiseEndpointSet(pec.participantId))
    val connectedMsg = ConnectedRespMsg_MFV_0_5(pd.agentKeyDID, pvk)
    ctx.send(connectedMsg, toRole=Option(Requester), fromRole=Option(Initiater))
  }

  private def storeTheirKey(did: DID, verKey: VerKey): Unit = {
    try {
      walletAPI.storeTheirKey(StoreTheirKey(did, verKey))
    } catch {
      case e: BadRequestErrorException if e.respCode == ALREADY_EXISTS.statusCode =>
        throw new BadRequestErrorException(CONN_STATUS_ALREADY_CONNECTED.statusCode)
    }
  }

  private def handleSignupMsg(): Unit = {
    ctx.apply(SignedUp())
    val signedUpRespMsg = SignedUpRespMsg_MFV_0_5()
    ctx.send(signedUpRespMsg, toRole = Option(Requester), fromRole = Option(Provisioner))
  }

  private def handleCreateAgentMsg(s:State.Signedup): Unit = {
    if (ConfigUtil.sponsorRequired(appConfig)) throw new BadRequestErrorException(PROVISIONING_PROTOCOL_DEPRECATED.statusCode)
    val fromDID = s.pdd.forDID
    val fromDIDVerKey = getVerKeyReqViaCache(fromDID)
    val aws = s.parameters.paramValueRequired(NEW_AGENT_WALLET_ID)
    val agentPairwiseKey = prepareNewAgentWalletData(fromDID, fromDIDVerKey, aws)
    ctx.apply(AgentPairwiseKeyCreated(agentPairwiseKey.did, agentPairwiseKey.verKey))
    val endpointDetail = s.parameters.paramValueRequired(CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON)
    ctx.signal(AskUserAgentCreator(fromDID, agentPairwiseKey.did, endpointDetail))
  }

  private def handleAgentCreated(akc: State.AgentKeyCreated): Unit = {
    val agentCreatedRespMsg = AgentCreatedRespMsg_MFV_0_5(akc.did, akc.verKey)
    ctx.apply(UserAgentCreated())
    ctx.send(agentCreatedRespMsg, toRole = Option(Requester), fromRole = Option(Provisioner))
  }

  override def walletAPI: WalletAPI = ctx.SERVICES_DEPRECATED.walletAPI
}

/**
  * Signal
  */
case class AskAgencyPairwiseCreator(newAgentKeyDID: DID, theirPairwiseDID: DID, endpointDetailJson: String)

case class PairwiseEndpointCreated(participantId: ParticipantId) extends Control with ActorMessageClass
