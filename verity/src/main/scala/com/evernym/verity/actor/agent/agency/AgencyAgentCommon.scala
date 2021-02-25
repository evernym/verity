package com.evernym.verity.actor.agent.agency

import akka.pattern.ask
import akka.event.LoggingReceive
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msghandler.AgentMsgHandler
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgParam}
import com.evernym.verity.actor.agent.msghandler.outgoing.MsgNotifier
import com.evernym.verity.actor.agent.user.{AgentProvisioningDone, GetSponsorRel}
import com.evernym.verity.actor.agent.{AgentActorDetailSet, DidPair, SetAgentActorDetail, SetupAgentEndpoint_V_0_7, SponsorRel}
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet, GetVerKey, NewKeyCreated, StoreTheirKey, TheirKeyStored, WalletCreated}
import com.evernym.verity.actor.{ConnectionStatusUpdated, ForIdentifier, ShardRegionFromActorContext}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.CommonConfig.PROVISIONING
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.constants.ActorNameConstants.AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.legacy.services.{CreateAgentEndpointDetail, CreateKeyEndpointDetail}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily._
import com.evernym.verity.util.ParticipantUtil
import com.evernym.verity.util.Util.getNewActorId
import com.evernym.verity.vault.WalletAPIParam

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

  val commonCmdReceiver: Receive = LoggingReceive.withLabel("commonCmdReceiver") {
    case GetSponsorRel                  => sender ! sponsorRel.getOrElse(SponsorRel.empty)
    case saw: SetAgentActorDetail       => setAgentActorDetail(saw)
//    case apd: AgentProvisioningDone     =>
//      //dhh Why is this message untyped?
//      sendToAgentMsgProcessor(ProcessUntypedMsgV2(
//        CompleteAgentProvisioning(apd.selfDID, apd.agentVerKey),
//        AgentProvisioningDefinition,
//        apd.threadId
//      ))
  }

  override val receiveAgentSpecificInitCmd: Receive = LoggingReceive.withLabel("receiveActorInitSpecificCmd") {
    case saw: SetAgentActorDetail               => setAgentActorDetail(saw)
  }

  def setAgencyAndOwnerDetail(didPair: DidPair): Unit = {
    setAgencyDIDPair(didPair)
  }

  def setAgentActorDetail(saw: SetAgentActorDetail): Unit = {
    logger.debug("'SetAgentActorDetail' received", (LOG_KEY_PERSISTENCE_ID, persistenceId))
    setAgencyAndOwnerDetail(saw.didPair)
    updateAgentWalletId(saw.actorEntityId)
    sender ! AgentActorDetailSet(saw.didPair, saw.actorEntityId)
  }

  override def agencyDidPairFutByCache(agencyDID: DID): Future[DidPair] = {
    state.agencyDIDPair match {
      case Some(adp) if adp.DID.nonEmpty && adp.verKey.nonEmpty => Future(adp)
      case _ if state.agentWalletId.isDefined =>
        walletAPI.executeAsync[VerKey](GetVerKey(agencyDID)).map { vk =>
          DidPair(agencyDID, vk)
        }
      case _ => super.agencyDidPairFutByCache(agencyDID)
    }
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
          case THIS_AGENT_WALLET_ID                	    => Parameter(THIS_AGENT_WALLET_ID, agentWalletIdReq)
          case NEW_AGENT_WALLET_ID                      => Parameter(NEW_AGENT_WALLET_ID, newActorId)
          case CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON    => Parameter(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON, keyEndpointJson)
          case CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON  => Parameter(CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON, agentEndpointJson)
          case DEFAULT_ENDORSER_DID                     => Parameter(DEFAULT_ENDORSER_DID, defaultEndorserDid)

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
      CreateKeyEndpointDetail(AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
        ownerDIDReq,
        ownerAgentKeyDIDPair)
    )

    lazy val agentEndpointJson = DefaultMsgCodec.toJson(
      CreateAgentEndpointDetail(userAgentRegionName, newActorId))

    mapper(newActorId, keyEndpointJson, agentEndpointJson)
  }

  def stateDetailsFor: Future[PartialFunction[String, Parameter]] = {
    for (
      agencyDidPair <- agencyDidPairFut()
    ) yield  {
      stateDetailsWithAgencyVerKey(agencyDidPair.verKey)
    }
  }

  override final def handleSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] =
    handleCommonSignalMsgs orElse handleSpecificSignalMsgs

  def handleCommonSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] =
    handleCoreSignalMsgs orElse handleLegacySignalMsgs

  def handleCoreSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = {
    case SignalMsgParam(cr: ConnectionStatusUpdated, _)                 => writeAndApply(cr); Future.successful(None)
    case SignalMsgParam(idSponsor: IdentifySponsor, _)                  => identifySponsor(idSponsor)
    case SignalMsgParam(provNeeded: ProvisioningNeeded, Some(threadId)) => provisionAgent(provNeeded, threadId)
  }

  def handleSpecificSignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = PartialFunction.empty

  def identifySponsor(idSponsor: IdentifySponsor): Future[Option[ControlMsg]] = {
    val sponsorRequired = ConfigUtil.sponsorRequired(appConfig)
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
    val provParamFut = requester match {
      case NeedsCloudAgent(requesterKeys, _) =>
        Future.successful(ProvisioningParam(requesterKeys.fromDID, requesterKeys.fromVerKey, requesterKeys.fromVerKey))
      case NeedsEdgeAgent(requesterVk, _) =>
        agentActorContext.walletAPI.executeAsync[NewKeyCreated](CreateNewKey()).map { nk =>
          ProvisioningParam(nk.did, nk.verKey, requesterVk)
        }
    }
    provParamFut.flatMap { pp =>
      prepareNewAgentWalletData(pp.domainDID, pp.domainVerKey, newActorId).flatMap { agentPairwiseKey =>
        val setupEndpoint = SetupAgentEndpoint_V_0_7(
          threadId,
          DidPair(pp.domainDID, pp.domainVerKey) ,
          DidPair(agentPairwiseKey.did, agentPairwiseKey.verKey),
          pp.requestVerKey,
          requester.sponsorRel
        )
        val fut = userAgentRegion ? ForIdentifier(newActorId, setupEndpoint)
        fut.mapTo[AgentProvisioningDone].map { apd =>
          Option(ControlMsg(CompleteAgentProvisioning(apd.selfDID, apd.agentVerKey)))
        }
      }
    }
  }

  def prepareNewAgentWalletData(requesterDid: DID, requesterVerKey: VerKey, walletId: String): Future[NewKeyCreated]  = {
    implicit val wap: WalletAPIParam = WalletAPIParam(walletId)
    val fut1 = walletAPI.executeAsync[WalletCreated.type](CreateWallet)
    val fut2 = walletAPI.executeAsync[TheirKeyStored](StoreTheirKey(requesterDid, requesterVerKey))
    val fut3 = walletAPI.executeAsync[NewKeyCreated](CreateNewKey())
    fut1.map { _ =>
      //below futures should be only executed when fut1 (create wallet) is done
      for (
        _       <- fut2;
        fut3Res <- fut3
      ) yield fut3Res
    }.flatten
  }

  def selfParticipantId: ParticipantId = ParticipantUtil.participantId(state.thisAgentKeyDIDReq, None)

  override def sponsorRel: Option[SponsorRel] = Option(SponsorRel.empty)
}

case class ProvisioningParam(domainDID: DID, domainVerKey: VerKey, requestVerKey: VerKey)
