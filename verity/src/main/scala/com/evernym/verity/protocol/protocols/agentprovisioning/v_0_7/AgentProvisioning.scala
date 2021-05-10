package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7

import com.evernym.verity.Base64Encoded
import com.evernym.verity.actor.agent.SponsorRel
import com.evernym.verity.actor.wallet.VerifySigResult
import com.evernym.verity.actor.{ParameterStored, ProtocolInitialized}
import com.evernym.verity.constants.InitParamConstants.DATA_RETENTION_POLICY
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.container.actor.Init
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.StoredSegment
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{ProvisionToken, _}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.State.{CloudWaitingOnSponsor, EdgeCreationWaitingOnSponsor, FailedAgentCreation, Initialized, Provisioning, RequestedToProvision, Uninitialized, AgentCreated => AgentCreatedState}
import com.evernym.verity.util.Base64Util.getBase64Decoded
import com.evernym.verity.util.TimeUtil._

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

trait AgentProvisionEvt

class AgentProvisioning(val ctx: ProtocolContextApi[AgentProvisioning, Role, Msg, Any, AgentProvisioningState, String])
  extends Protocol[AgentProvisioning,Role,Msg,Any,AgentProvisioningState,String](AgentProvisioningDefinition) {

  override def handleProtoMsg: (AgentProvisioningState, Option[Role], Msg) ?=> Any = {
    case (Initialized(_) | FailedAgentCreation(_), _,                m: CreateCloudAgent)   =>
      provisionCloudRole(m.requesterKeys, m.provisionToken, _otherIdx, _selfIdx)
      ctx.signal(IdentifySponsor(m.provisionToken))

    case (Initialized(_) | FailedAgentCreation(_), _,                m: CreateEdgeAgent)   =>
      provisionEdgeRole(m.requesterVk, m.provisionToken, _otherIdx, _selfIdx)
      ctx.signal(IdentifySponsor(m.provisionToken))

    case (_: RequestedToProvision, Some(Provisioner), m: AgentCreated)  =>
      ctx.apply(AgentProvisioned(m.selfDID, m.agentVerKey))

    case (_, _, e: ProblemReport) =>
      ctx.apply(ProvisionFailed(e.msg))

      //once agent is created, and if this protocol receives create agent request again
      // we shouldn't change protocol's state to 'FailedAgentCreation',
      // just send the problem report
    case (_: AgentCreatedState,     _,                m: CreateCloudAgent)   =>
      problemReport(_duplicateAgent(m.requesterKeys), Some(_duplicateAgent(m.requesterKeys)))
    case (_: AgentCreatedState,     _,                m: CreateEdgeAgent)   =>
      problemReport(_duplicateAgent(m.requesterVk), Some(_duplicateAgent(m.requesterVk)))
  }

  override def handleControl: Control ?=> Any = {
    case c => mainHandleControl(ctx.getState, ctx.getRoster.selfRole, c)
  }

  def mainHandleControl: (AgentProvisioningState, Option[Role], Control) ?=> Any = {
    case (_: Uninitialized, None, c: Init)                                                  =>
      ctx.apply(ProtocolInitialized(c.parametersStored.toSeq))

    case (Initialized(_) | FailedAgentCreation(_), None | Some(Requester), c: ProvisionCloudAgent) =>
      provisionCloudRole(c.requesterKeys, c.provisionToken, _selfIdx, _otherIdx)
      ctx.send(c.asCreateAgent())

    case (Initialized(_) | FailedAgentCreation(_), None | Some(Requester), c: ProvisionEdgeAgent) =>
      provisionEdgeRole(c.requesterVk, c.provisionToken, _selfIdx, _otherIdx)
      ctx.send(c.asCreateAgent())

    case (s: AwaitsSponsor, Some(Provisioner), GiveSponsorDetails(d, cacheUsedTokens, window)) =>
      askForProvisioningIfValidSponsor(d, ProvisionToken(s.token.get), cacheUsedTokens, window, provisioningSignal(s))

    case (s: AwaitsSponsor, Some(Provisioner), NoSponsorNeeded())                       =>
      askForProvisioning(provisioningSignal(s))

    case (_: AwaitsSponsor, Some(Provisioner), InvalidToken())                          =>
      provisioningFailed(MissingToken.err)

    case (_: Provisioning, Some(Provisioner), _: AlreadyProvisioned)                    =>
      provisioningFailed(AlreadyProvisionedProblem.err)

    case (_: Provisioning, Some(Provisioner), cap: CompleteAgentProvisioning)           =>
      ctx.apply(AgentProvisioned(cap.selfDID, cap.agentVerKey))
      ctx.send(AgentCreated(cap.selfDID, cap.agentVerKey))
  }

  override def applyEvent: ApplyEvent = {
    case (_,                        _, e: ProtocolInitialized   )                  =>
      (State.Initialized(getInitParams(e)), initialize(e.parameters))

    case (Initialized(_) | FailedAgentCreation(_), _, e: RequestedAgentCreation)
      if _isRequester(e.setter.get)                                                =>
      (
        RequestedToProvision(),
        setRoles(e.setter.get)
      )

    case (Initialized(_) | FailedAgentCreation(_), _, e: RequestedAgentCreation)
      if !_isRequester(e.setter.get)                                               =>
      (
        CloudWaitingOnSponsor(RequesterKeys(e.keys).get, e.tokenDetails),
        setRoles(e.setter.get)
      )

    case (Initialized(_) | FailedAgentCreation(_), _, e: RequestedEdgeAgentCreation)
      if _isRequester(e.setter.get)                                                =>
      (
        RequestedToProvision(),
        setRoles(e.setter.get)
      )

    case (Initialized(_) | FailedAgentCreation(_), _, e: RequestedEdgeAgentCreation)
      if !_isRequester(e.setter.get)                                               =>
      (
        EdgeCreationWaitingOnSponsor(e.requesterVk, e.tokenDetails),
        setRoles(e.setter.get)
      )

    case (_: AwaitsSponsor,                     _, _: AskedForProvisioning)    =>
      Provisioning()

    case (_: Provisioning,                          _, e: AgentProvisioned)        =>
      AgentCreatedState(e.selfDID, e.agentVerKey)

    case (_: RequestedToProvision,                  _, e: AgentProvisioned)        =>
      AgentCreatedState(e.selfDID, e.agentVerKey)

    case (_,                                        _, e: ProvisionFailed)         =>
     FailedAgentCreation(e.err)

  }

  def withTokenEvt(fn: () => Unit): Unit = {
    try {
      fn()
    } catch {
      case e: ProvisioningException => provisioningFailed(e.err)
      case e: Exception => provisioningFailed(e.getMessage)
    }
  }

  def provisionEdgeRole(requesterVk: VerKey,
                        provisionToken: Option[ProvisionToken],
                        r: ParticipantIndex,
                        p: ParticipantIndex): Unit = {
    withTokenEvt(() => {
      ctx.apply(RequestedEdgeAgentCreation(
        Some(SetRoster(r, p)),
        requesterVk,
        provisionToken.map(x => x.asEvent())
      ))
    })
  }

  private def provisionCloudRole(requesterKeys: RequesterKeys,
                                 provisionToken: Option[ProvisionToken],
                                 r: ParticipantIndex,
                                 p: ParticipantIndex): Unit = {
    withTokenEvt(() => {
      ctx.apply(RequestedAgentCreation(
        Some(SetRoster(r, p)),
        Some(requesterKeys.asEvent()),
        provisionToken.map(x => x.asEvent())
      ))
    })
  }

  private def isValidSignature(token: ProvisionToken, sponsorVk: VerKey)(handler: Try[VerifySigResult] => Unit): Unit = {
    val msg = (token.nonce + token.timestamp + token.sponseeId + token.sponsorId).getBytes
    ctx.wallet.verify(msg, getBase64Decoded(token.sig), sponsorVk, SIGN_ED25519_SHA512_SINGLE){handler(_)}
  }

  private def askForProvisioningIfValidSponsor(sponsorDetailsOpt: Option[SponsorDetails],
                                               token: ProvisionToken,
                                               cacheUsedTokens: Boolean,
                                               window: Duration,
                                               sig: Signal): Unit = {
    try {
      val sponsorDetails = sponsorDetailsOpt.getOrElse(throw NoSponsor)

      if(!sponsorDetails.active) throw SponsorInactive

      if(!isWithinRange(token.timestamp, window)) throw ProvisionTimeout

      val sponsorVerKey = sponsorDetails.keys.find(_.verKey.equals(token.sponsorVerKey)).getOrElse(throw InvalidSponsorVerKey)

      isValidSignature(token, sponsorVerKey.verKey) {
        case Success(vsg) if vsg.verified =>
          if (cacheUsedTokens) {
            ctx.withSegment[AskedForProvisioning](token.sig) {
              case Success(None)    => storeSig(token.sig, sponsorDetails, sig)
              case Success(Some(_)) => provisioningFailed(DuplicateProvisionedApp)
              case Failure(exception) => provisioningFailed(exception.getMessage, Option(exception.getMessage))
            }
          } else {
            ctx.logger.debug(s"ask for provisioning (without caching token): $sponsorDetails")
            askForProvisioning(sig)
          }
        case _ =>
          provisioningFailed(InvalidSignature)
      }
    } catch {
      case e: ProvisioningException => provisioningFailed(e.err, Option(e.err))
      case NonFatal(e) => provisioningFailed(e.getMessage)
    }
  }

  def storeSig(tokenSig: Base64Encoded, sponsorDetails: SponsorDetails, sig: Signal): Unit = {
    ctx.storeSegment(tokenSig, AskedForProvisioning()) {
      case Success(_: StoredSegment) =>
        ctx.logger.debug(s"ask for provisioning (with caching token): $sponsorDetails")
        askForProvisioning(sig)
      case Failure(e) => problemReport(e.getMessage, Some(e.getMessage))
    }
  }
  def provisioningSignal(requesterState: AwaitsSponsor): Signal = {
    val sponsorRel = requesterState.token.map(t => SponsorRel(t.sponsorId, t.sponseeId))
    requesterState match {
      case x: CloudWaitingOnSponsor =>
        NeedsCloudAgent(x.requesterKeys, sponsorRel)
      case x: EdgeCreationWaitingOnSponsor =>
        NeedsEdgeAgent(x.requesterVk, sponsorRel)
    }
  }

  def askForProvisioning(sig: Signal): Unit = {
    ctx.apply(AskedForProvisioning())
    ctx.signal(sig)
  }

  def initialize(params: Seq[ParameterStored]): Roster[Role] = {
    //TODO: this still feels like boiler plate, need to come back and fix it
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
  }

  def setRoles(r: SetRoster): Roster[Role] = ctx.getRoster.withAssignment(
    Requester -> r.requesterIdx,
    Provisioner -> r.provisionerIdx,
  )

  def getInitParams(params: ProtocolInitialized): Parameters =
    Parameters(params
      .parameters
      .map(p => Parameter(p.name, p.value))
      .toSet
    )

  def provisioningFailed(ex: ProvisioningException): Unit = {
    provisioningFailed(ex.err, Option(ex.err))
  }

  def provisioningFailed(logErr: String, optMsg: Option[String]=None): Unit = {
    ctx.apply(ProvisionFailed(logErr))
    problemReport(logErr, optMsg)
  }

  def problemReport(logErr: String, optMsg: Option[String]=None): Unit = {
    ctx.logger.info(logErr)
    ctx.send(ProblemReport(optMsg.getOrElse(DefaultProblem.err)))
  }

  def _isRequester(setter: SetRoster): Boolean = setter.requesterIdx == _selfIdx
  def _otherIdx: ParticipantIndex = ctx.getRoster.otherIndex(ctx.getRoster.selfIndex_!)
  def _selfIdx: ParticipantIndex = ctx.getRoster.selfIndex_!
  def _duplicateAgent(keys: Any): String =
    s"Agent already created for $keys"
}
