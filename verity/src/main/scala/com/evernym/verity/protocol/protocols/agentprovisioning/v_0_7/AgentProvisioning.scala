package com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7

import com.evernym.verity.actor.{ParameterStored, ProtocolInitialized}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.actor.Init
import com.evernym.verity.protocol.engine.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.{ProvisionToken, _}
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.State.{CloudWaitingOnSponsor, EdgeCreationWaitingOnSponsor, FailedAgentCreation, Initialized, Provisioning, RequestedToProvision, Uninitialized, AgentCreated => AgentCreatedState}
import com.evernym.verity.util.TimeUtil._
import com.evernym.verity.util.Base64Util.getBase64Decoded

import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

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

    case (_: AgentCreatedState,     _,                m: CreateCloudAgent)   =>
      problemReport(_duplicateAgent(m.requesterKeys), Some(_duplicateAgent(m.requesterKeys)))
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
      if(hasValidSponsor(d, ProvisionToken(s.token.get), cacheUsedTokens, window))
        askForProvisioning(provisioningSignal(s))

    case (s: AwaitsSponsor, Some(Provisioner), NoSponsorNeeded())                       =>
      askForProvisioning(provisioningSignal(s))

    case (_: AwaitsSponsor, Some(Provisioner), InvalidToken())                          =>
      problemReport(MissingToken.err)

    case (_: Provisioning, Some(Provisioner), x: CompleteAgentProvisioning)             =>
      ctx.apply(AgentProvisioned(x.selfDID, x.agentVerKey))
      ctx.send(AgentCreated(x.selfDID, x.agentVerKey))
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
      case e: Exception => problemReport(e.getMessage)
      case e: ProvisioningException => problemReport(e.err)
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

  private def isValidSignature(token: ProvisionToken, sponsorVk: VerKey): Boolean = {
    val msg = (token.nonce + token.timestamp + token.sponseeId + token.sponsorId).getBytes
    ctx.wallet.verify(msg, getBase64Decoded(token.sig), sponsorVk, SIGN_ED25519_SHA512_SINGLE)
      .recover {
        case ex => ctx.logger.error(s"${InvalidSignature.err} - ${ex.getMessage}"); false
      }.getOrElse(false)
  }

  private def hasValidSponsor(sponsorDetailsOpt: Option[SponsorDetails],
                              token: ProvisionToken,
                              cacheUsedTokens: Boolean,
                              window: Duration): Boolean = {
    try {
      val sponsorDetails = sponsorDetailsOpt.getOrElse(throw NoSponsor)

      if(!sponsorDetails.active) throw SponsorInactive

      if(!isWithinRange(token.timestamp, window)) throw ProvisionTimeout

      val sponsorVerKey = sponsorDetails.keys.find(_.verKey.equals(token.sponsorVerKey)).getOrElse(throw InvalidSponsorVerKey)

      if(!isValidSignature(token, sponsorVerKey.verKey)) throw InvalidSignature

      if (cacheUsedTokens) {
        if (ctx.getInFlight.segmentAs[AskedForProvisioning].isDefined)
          throw DuplicateProvisionedApp
        else {
          ctx.storeSegment(token.sig, AskedForProvisioning())
        }
      }

      ctx.logger.debug((s"ask for provisioning: $sponsorDetails"))
      true
    } catch {
      case e: ProvisioningException => problemReport(e.err, Option(e.err)); false
      case NonFatal(e) => problemReport(e.getMessage); false
    }
  }

  def provisioningSignal(requesterState: AwaitsSponsor): Signal = {
    val sponsorId = requesterState.token.map(_.sponsorId)
    val sponseeId = requesterState.token.map(_.sponseeId)
    requesterState match {
      case x: CloudWaitingOnSponsor =>
        NeedsCloudAgent(x.requesterKeys, sponsorId, sponseeId)
      case x: EdgeCreationWaitingOnSponsor =>
        NeedsEdgeAgent(x.requesterVk, sponsorId, sponseeId)
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

  def problemReport(logErr: String, optMsg: Option[String]=None): Unit = {
    ctx.logger.error(logErr)
    ctx.apply(ProvisionFailed(logErr))
    ctx.send(ProblemReport(optMsg.getOrElse(DefaultProblem.err)))
  }

  def _isRequester(setter: SetRoster): Boolean = setter.requesterIdx == _selfIdx
  def _otherIdx: ParticipantIndex = ctx.getRoster.otherIndex(ctx.getRoster.selfIndex_!)
  def _selfIdx: ParticipantIndex = ctx.getRoster.selfIndex_!
  def _duplicateAgent(keys: RequesterKeys): String =
    s"Agent already created for $keys"
}
