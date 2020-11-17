package com.evernym.verity.actor.agent.agency

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msghandler.AgentMsgHandler
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgFromDriver}
import com.evernym.verity.actor.agent.msghandler.outgoing.MsgNotifier
import com.evernym.verity.actor.agent.{AgentActorDetailSet, SetAgentActorDetail, SetupAgentEndpoint_V_0_7}
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.actor.{ConnectionStatusUpdated, ForIdentifier, ShardRegionFromActorContext}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.cache.{CacheQueryResponse, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.config.CommonConfig.PROVISIONING
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.constants.ActorNameConstants.AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.legacy.services.{CreateAgentEndpointDetail, CreateKeyEndpointDetail}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily._
import com.evernym.verity.util.ParticipantUtil
import com.evernym.verity.util.Util.getNewActorId
import com.evernym.verity.vault.{NewKeyCreated, StoreTheirKeyParam, WalletAccessParam}

import scala.concurrent.Future
import scala.concurrent.duration.Duration

/**
 * common logic between 'AgencyAgent' and 'AgencyAgentPairwise' actor
 */
trait AgencyAgentCommon
  extends AgentPersistentActor
    with AgentMsgHandler
    with ShardRegionFromActorContext
    with MsgNotifier
    with LEGACY_connectingSignalHandler {

  def setAgencyAndOwnerDetail(aDID: DID): Unit = {
    setAgencyDID(aDID)
  }

  def setAgentActorDetail(saw: SetAgentActorDetail): Unit = {
    logger.debug("'SetAgentActorDetail' received", (LOG_KEY_PERSISTENCE_ID, persistenceId))
    setAgencyAndOwnerDetail(saw.did)
    setAndOpenWalletIfExists(saw.actorEntityId)
    sender ! AgentActorDetailSet(saw.did, saw.actorEntityId)
  }

  def stateDetailsWithAgencyVerKey(agencyVerKey: VerKey): PartialFunction[String, Parameter] = {
        def mapper(newActorId: String,
                   keyEndpointJson: String,
                   agentEndpointJson: String
                  ): PartialFunction[String, Parameter] = {
          case SELF_ID                                  => Parameter(SELF_ID, selfParticipantId)
          case NAME                                	    => Parameter(NAME, "agency")
          case LOGO_URL                            	    => Parameter(LOGO_URL, "agency-logo-url")
          case AGENT_PROVISIONER_PARTICIPANT_ID         => Parameter(AGENT_PROVISIONER_PARTICIPANT_ID, selfParticipantId)
          case AGENCY_DID                               => Parameter(AGENCY_DID, agencyDIDReq)
          case AGENCY_DID_VER_KEY                       => Parameter(AGENCY_DID_VER_KEY, agencyVerKey)
          case THIS_AGENT_WALLET_SEED                	  => Parameter(THIS_AGENT_WALLET_SEED, agentWalletSeedReq)
          case NEW_AGENT_WALLET_SEED                    => Parameter(NEW_AGENT_WALLET_SEED, newActorId)
          case CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON    => Parameter(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON, keyEndpointJson)
          case CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON  => Parameter(CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON, agentEndpointJson)

          //TODO: below parameter is required by dead drop protocol (but not used by it if it is running on cloud agency)
          case OTHER_ID                                 => Parameter(OTHER_ID, "")

          //TODO: below parameters are required by connecting protocol based on the context from where it is running
          //if it is running at agency agent level, it doesn't need it, but if it is running at user agent level, it does need it
          //we may have to find better solution for this
          case MY_PUBLIC_DID                            => Parameter(MY_PUBLIC_DID, "")
          case MY_SELF_REL_DID                          => Parameter(MY_SELF_REL_DID, "")
        }

    lazy val newActorId = getNewActorId

    lazy val keyEndpointJson = DefaultMsgCodec.toJson(
      CreateKeyEndpointDetail(AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME, ownerDIDReq, ownerAgentKeyDID))

    lazy val agentEndpointJson = DefaultMsgCodec.toJson(
      CreateAgentEndpointDetail(userAgentRegionName, newActorId))

    mapper(newActorId, keyEndpointJson, agentEndpointJson)
  }

  def stateDetailsFor: Future[PartialFunction[String, Parameter]] = {
    for (
      agencyVerKey <- getAgencyVerKeyFut
    ) yield  {
      stateDetailsWithAgencyVerKey(agencyVerKey)
    }
  }

  override final def handleSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] =
    handleCommonSignalMsgs orElse handleSpecificSignalMsgs

  def handleCommonSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] =
    handleCoreSignalMsgs orElse handleLegacySignalMsgs

  def handleCoreSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = {
    case SignalMsgFromDriver(cr: ConnectionStatusUpdated, _, _, _)             => writeAndApply(cr); Future.successful(None)
    case SignalMsgFromDriver(idSponsor: IdentifySponsor, _, _, _)              => identifySponsor(idSponsor)
    case SignalMsgFromDriver(provisioningNeeded: ProvisioningNeeded, _,_, tcd) => provisionAgent(provisioningNeeded, tcd.threadId)
  }

  def handleSpecificSignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = PartialFunction.empty

  def identifySponsor(idSponsor: IdentifySponsor): Future[Option[ControlMsg]] = {
    val sponsorRequired = appConfig.getLoadedConfig.getBoolean(s"$PROVISIONING.sponsor-required")
    val tokenWindow = Duration(appConfig.getLoadedConfig.getString(s"$PROVISIONING.token-window"))
    val cacheUsedTokens = appConfig.getConfigBooleanOption(s"$PROVISIONING.cache-used-tokens").getOrElse(false)

    logger.debug(s"identify sponsor: $sponsorRequired - token valid for $tokenWindow")
    val ctl: AgentProvisioningMsgFamily.Ctl = idSponsor.provisionDetails match {
      case Some(details) if sponsorRequired   =>
        val sponsorDetails = ConfigUtil.findSponsorConfigWithId(details.sponsorId, appConfig)
        GiveSponsorDetails(sponsorDetails, cacheUsedTokens, tokenWindow)
      case None          if sponsorRequired   => InvalidToken()
      case _             if !sponsorRequired  => NoSponsorNeeded()
    }
    logger.debug(s"sponsor information: $ctl")
    Future.successful(Some(ControlMsg(ctl)))
  }

  def provisionAgent(requester: ProvisioningNeeded, threadId: ThreadId): Future[Option[ControlMsg]] = {
    logger.debug(s"Cloud Agent provisioning requested: $requester")

    val newActorId = getNewActorId
    val (domainDID, domainVk, requesterVk) = requester match {
      case NeedsCloudAgent(requesterKeys, _) =>
        (requesterKeys.fromDID, requesterKeys.fromVerKey, requesterKeys.fromVerKey)
      case NeedsEdgeAgent(requesterVk, _) =>
        val domainKeys = agentActorContext.walletAPI.createNewKey()
        (domainKeys.did, domainKeys.verKey, requesterVk)
    }

    val agentPairwiseKey = prepareNewAgentWalletData(domainDID, domainVk, newActorId)
    val setupEndpoint = SetupAgentEndpoint_V_0_7(
      threadId,
      domainDID,
      agentPairwiseKey.did,
      requesterVk,
      requester.sponsorRel
    )

    userAgentRegion ! ForIdentifier(newActorId, setupEndpoint)

    Future.successful(None)
  }

  def prepareNewAgentWalletData(requesterDid: DID, requesterVerKey: VerKey, seed: String): NewKeyCreated  = {
    val wap = WalletAccessParam(seed, agentActorContext.walletAPI, agentActorContext.walletConfig,
      agentActorContext.appConfig, closeAfterUse=false)
    agentActorContext.walletAPI.createAndOpenWallet(wap)
    agentActorContext.walletAPI.storeTheirKey(StoreTheirKeyParam(requesterDid, requesterVerKey))(wap)
    agentActorContext.walletAPI.createNewKey()(wap)
  }

  def getAgencyDIDFut(req: Boolean = false): Future[CacheQueryResponse] = {
    val gcop = GetCachedObjectParam(Set(KeyDetail(AGENCY_DID, required = req)), KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID)
    generalCache.getByParamAsync(gcop)
  }

  def selfParticipantId: ParticipantId = ParticipantUtil.participantId(state.thisAgentKeyDIDReq, None)
}
