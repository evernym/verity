package com.evernym.verity.actor.agent.agency

import akka.pattern.ask
import akka.event.LoggingReceive
import com.evernym.verity.actor.agent.msghandler.AgentMsgHandler
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgParam}
import com.evernym.verity.actor.agent.msghandler.outgoing.MsgNotifier
import com.evernym.verity.actor.agent.msgrouter.RouteAlreadySet
import com.evernym.verity.actor.agent.user.{AgentProvisioningDone, GetSponsorRel}
import com.evernym.verity.actor.agent.{AgentActorDetailSet, DidPair, SetAgentActorDetail, SetupAgentEndpoint_V_0_7, SponsorRel}
import com.evernym.verity.actor.persistence.AgentPersistentActor
import com.evernym.verity.actor.wallet.{AgentWalletSetupCompleted, GetVerKey, GetVerKeyResp, SetupNewAgentWallet}
import com.evernym.verity.actor.{ConnectionStatusUpdated, ForIdentifier, ShardRegionFromActorContext}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.ConfigConstants.PROVISIONING
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.constants.ActorNameConstants.AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.legacy.services.{CreateAgentEndpointDetail, CreateKeyEndpointDetail}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily._
import com.evernym.verity.util.ParticipantUtil
import com.evernym.verity.util.Util.getNewActorId
import com.evernym.verity.vault.WalletAPIParam

import scala.concurrent.{ExecutionContext, Future}
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
  private implicit val executionContext: ExecutionContext = futureExecutionContext

  val commonCmdReceiver: Receive = LoggingReceive.withLabel("commonCmdReceiver") {
    case GetSponsorRel                  => sender ! sponsorRel.getOrElse(SponsorRel.empty)
    case saw: SetAgentActorDetail       => setAgentActorDetail(saw)
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

  override def agencyDidPairFutByCache(agencyDID: DidStr): Future[DidPair] = {
    state.agencyDIDPair match {
      case Some(adp) if adp.DID.nonEmpty && adp.verKey.nonEmpty => Future(adp)
      case _ if state.agentWalletId.isDefined =>
        walletAPI.executeAsync[GetVerKeyResp](GetVerKey(agencyDID)).map { gvkr =>
          DidPair(agencyDID, gvkr.verKey)
        }
      case _ => super.agencyDidPairFutByCache(agencyDID)
    }
  }

  def stateDetailsWithAgencyVerKey(agencyVerKey: VerKeyStr, protoRef: ProtoRef): PartialFunction[String, Parameter] = {
        def mapper(newActorId: String,
                   keyEndpointJson: String,
                   agentEndpointJson: String
                  ): PartialFunction[String, Parameter] = {
          case SELF_ID => Parameter(SELF_ID, selfParticipantId)
          case NAME => Parameter(NAME, "agency")
          case LOGO_URL => Parameter(LOGO_URL, "agency-logo-url")
          case AGENT_PROVISIONER_PARTICIPANT_ID => Parameter(AGENT_PROVISIONER_PARTICIPANT_ID, selfParticipantId)
          case AGENCY_DID => Parameter(AGENCY_DID, agencyDIDReq)
          case AGENCY_DID_VER_KEY => Parameter(AGENCY_DID_VER_KEY, agencyVerKey)
          case THIS_AGENT_WALLET_ID => Parameter(THIS_AGENT_WALLET_ID, agentWalletIdReq)
          case NEW_AGENT_WALLET_ID => Parameter(NEW_AGENT_WALLET_ID, newActorId)
          case CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON => Parameter(CREATE_KEY_ENDPOINT_SETUP_DETAIL_JSON, keyEndpointJson)
          case CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON => Parameter(CREATE_AGENT_ENDPOINT_SETUP_DETAIL_JSON, agentEndpointJson)
          case DEFAULT_ENDORSER_DID => Parameter(DEFAULT_ENDORSER_DID, defaultEndorserDid)

          //TODO: below parameter is required by dead drop protocol (but not used by it if it is running on cloud agency)
          case OTHER_ID => Parameter(OTHER_ID, "")

          //TODO: below parameters are required by connecting protocol based on the context from where it is running
          //if it is running at agency agent level, it doesn't need it, but if it is running at user agent level, it does need it
          //we may have to find better solution for this
          case MY_PUBLIC_DID => Parameter(MY_PUBLIC_DID, "")
          case MY_SELF_REL_DID => Parameter(MY_SELF_REL_DID, "")
          case DATA_RETENTION_POLICY => Parameter(DATA_RETENTION_POLICY,
            ConfigUtil.getProtoStateRetentionPolicy(appConfig, domainId, protoRef.msgFamilyName).configString)
        }

    lazy val newActorId = getNewActorId

    lazy val keyEndpointJson = DefaultMsgCodec.toJson(
      CreateKeyEndpointDetail(
        AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME,
        ownerDIDReq,
        ownerAgentKeyDIDPair.map(d => com.evernym.verity.did.DidPair(d.DID, d.verKey))
      )
    )

    lazy val agentEndpointJson = DefaultMsgCodec.toJson(
      CreateAgentEndpointDetail(userAgentRegionName, newActorId))

    mapper(newActorId, keyEndpointJson, agentEndpointJson)
  }

  def stateDetailsFor: Future[ProtoRef => PartialFunction[String, Parameter]] = {
    for (
      agencyDidPair <- agencyDidPairFut()
    ) yield  {
      p: ProtoRef => stateDetailsWithAgencyVerKey(agencyDidPair.verKey, p)
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
    val cacheUsedTokens = appConfig.getBooleanOption(s"$PROVISIONING.cache-used-tokens").getOrElse(false)

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

    val (setupNewWalletCmd, requesterVerKey) = requester match {
      case NeedsCloudAgent(requesterKeys, _) =>
        (
          SetupNewAgentWallet(
            Option(
              com.evernym.verity.did.DidPair(
                requesterKeys.fromDID,
                requesterKeys.fromVerKey
              )
            )
          ),
          requesterKeys.fromVerKey
        )
      case NeedsEdgeAgent(requesterVk, _) =>
        //NOTE: earlier, at this point a "new key" used to get created in agency agent's wallet
        // and then that key used to stored in the newly to be provisioned user agent's wallet
        // which was wrong and it is fixed now, see more detail in this ticket: VE-2477
        // in future though, for previously (with old logic) provisioned edge agents,
        // if at all the user agent needs private keys of that key, it won't find it and
        // it may require those keys to be migrated to make them work.
        (SetupNewAgentWallet(None), requesterVk)
    }

    prepareNewAgentWalletData(setupNewWalletCmd, newActorId).flatMap { wsc =>
      val setupEndpoint = SetupAgentEndpoint_V_0_7(
        threadId,
        wsc.ownerDidPair.toAgentDidPair ,
        wsc.agentKey.didPair.toAgentDidPair,
        requesterVerKey,
        requester.sponsorRel.map(x => SponsorRel(x.sponsorId, x.sponseeId))
      )
      userAgentRegion ? ForIdentifier(newActorId, setupEndpoint)
    }.map {
      case apd: AgentProvisioningDone =>
        Option(ControlMsg(CompleteAgentProvisioning(apd.selfDID, apd.agentVerKey)))
      case _: RouteAlreadySet =>
        Option(ControlMsg(AlreadyProvisioned(requesterVerKey)))
    }
  }

  def prepareNewAgentWalletData(setupNewWalletCmd: SetupNewAgentWallet, walletId: String): Future[AgentWalletSetupCompleted]  = {
    implicit val wap: WalletAPIParam = WalletAPIParam(walletId)
    walletAPI.executeAsync[AgentWalletSetupCompleted](setupNewWalletCmd)
  }

  def selfParticipantId: ParticipantId = ParticipantUtil.participantId(state.thisAgentKeyDIDReq, None)

  override def sponsorRel: Option[SponsorRel] = Option(SponsorRel.empty)
}

case class ProvisioningParam(domainDID: DidStr, domainVerKey: VerKeyStr, requestVerKey: VerKeyStr)
