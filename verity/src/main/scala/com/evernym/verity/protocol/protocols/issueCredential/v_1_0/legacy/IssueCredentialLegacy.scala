package com.evernym.verity.protocol.protocols.issueCredential.v_1_0.legacy

import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.Protocol
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{Ack, IssueCred, OfferCred, ProposeCred, RequestCred}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Role.{Holder, Issuer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.{Ctl, Event, IssueCredential, IssueCredentialHelpers, ProtoMsg, Role, State}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.State.HasMyAndTheirDid


trait IssueCredentialLegacy
  extends Protocol[IssueCredential, Role, ProtoMsg, Event, State, String]
    with ProtocolHelpers[IssueCredential, Role, ProtoMsg, Event, State, String]
    with IssueCredentialHelpers {
  import IssueCredential._


  override type Context = ProtocolContextApi[IssueCredential, Role, ProtoMsg, Event, State, String]
  implicit val ctx: Context


  // TODO: Remove All Legacy control, protocol, and events during Ticket=VE-2605
  def legacyControl: Control ?=> Any = statefulHandleControl {
    case (_:State.ProposalReceivedLegacy    , _, m: Ctl.Offer  ) => handleOffer(m)
    case (st: State.OfferReceivedLegacy     , _, m: Ctl.Request) => handleRequest(m, st.myPwDid, st.credOffer)
    case (st: State.RequestReceivedLegacy   , _, m: Ctl.Issue  ) => handleIssue(m, st.credOffer, st.credRequest)
  }

  def legacyProtoMsg: (State, Option[Role], ProtoMsg) ?=> Any = {
    case (_ @ (_: State.Initialized |
               _: State.ProposalSentLegacy),  _, m: OfferCred)   => handleOfferCredReceived(m, ctx.getInFlight.sender.id_!)
    case (st: State.OfferSentLegacy,          _, m: RequestCred) => handleRequestCredReceived(m, st.credOffer, ctx.getInFlight.sender.id_!)
    case (_: State.RequestSentLegacy,         _, m: IssueCred)   => handleIssueCredReceived(m)
    case (_: State.CredSentLegacy,            _, m: Ack)         => handleAck(m)
  }

  def legacyApplyEvent: ApplyEvent = {
    case (st: HasMyAndTheirDid, _, e: ProposalSentLegacy) =>
      val proposal: ProposeCred = buildProposedCred(e.proposal)
      (
        Option(State.ProposalSentLegacy(st.myPwDid, st.theirPwDid, proposal)),
        setSenderRole(e.senderId, Holder(), ctx.getRoster)
      )

    case (st: HasMyAndTheirDid, _, e: ProposalReceivedLegacy) =>
      val proposal: ProposeCred = buildProposedCred(e.proposal)
      (
        Option(State.ProposalReceivedLegacy(st.myPwDid, st.theirPwDid, proposal)),
        setSenderRole(e.senderId, Holder(), ctx.getRoster)
      )

    case (st: HasMyAndTheirDid, _, e: OfferSentLegacy) =>
      val offer: OfferCred = buildOfferCred(e.offer)
      (
        Option(State.OfferSentLegacy(st.myPwDid, st.theirPwDid, offer, e.autoIssue)),
        setSenderRole(e.senderId, Issuer(), ctx.getRoster)
      )

    case (st: HasMyAndTheirDid, _, e: OfferReceivedLegacy) =>
      val offer: OfferCred = buildOfferCred(e.offer)
      (
        Option(State.OfferReceivedLegacy(st.myPwDid, st.theirPwDid, offer)),
        setSenderRole(e.senderId, Issuer(), ctx.getRoster)
      )

    case (st: State.OfferReceivedLegacy, _, e: RequestSentLegacy) =>
      val request: RequestCred = buildRequestCred(e.request)
      (
        Option(State.RequestSentLegacy(st.myPwDid, st.theirPwDid, st.credOffer, request)),
        setSenderRole(e.senderId, Holder(), ctx.getRoster)
      )

    case (st: State.OfferSentLegacy, _, e: RequestReceivedLegacy) =>
      val request: RequestCred = buildRequestCred(e.request)
      (
        Option(State.RequestReceivedLegacy(st.myPwDid, st.theirPwDid, st.credOffer, request)),
        setSenderRole(e.senderId, Issuer(), ctx.getRoster)
      )

    case (st: HasMyAndTheirDid, _, e: IssueCredSentLegacy) =>
      val issueCred: IssueCred = buildIssueCred(e.cred)
      State.CredSentLegacy(st.myPwDid, st.theirPwDid, issueCred)

    case (st: HasMyAndTheirDid, _, e: IssueCredReceivedLegacy) =>
      val issueCred: IssueCred = buildIssueCred(e.cred)
      State.CredReceivedLegacy(st.myPwDid, st.theirPwDid, issueCred)
  }
}
