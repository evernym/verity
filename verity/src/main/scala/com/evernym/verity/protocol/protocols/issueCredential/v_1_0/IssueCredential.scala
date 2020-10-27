package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.constants.InitParamConstants.{MY_PAIRWISE_DID, THEIR_PAIRWISE_DID}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.ledger.GetCredDefResp
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.conventions.CredValueEncoderV1_0
import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor._
import com.evernym.verity.protocol.didcomm.decorators.{Base64, AttachmentDescriptor, PleaseAck}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg._
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.ProblemReportCodes._
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Role.{Holder, Issuer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.SignalMsg.StatusReport
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.State.{HasMyAndTheirDid, PostInteractionStarted}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProtocolHelpers
import org.json.JSONObject

import scala.util.{Failure, Success}

//protocol document:
// https://github.com/hyperledger/aries-rfcs/blob/527849e/features/0036-issue-credential/README.md

class IssueCredential(implicit val ctx: ProtocolContextApi[IssueCredential, Role, Msg, Event, State, String])
  extends Protocol[IssueCredential, Role, Msg, Event, State, String](IssueCredentialProtoDef)
    with ProtocolHelpers[IssueCredential, Role, Msg, Event, State, String] {

  override def handleControl: Control ?=> Any = statefulHandleControl {
    case (State.Uninitialized()                               , _, m: Ctl.Init       ) => handleInit(m)

    case (_: State.Initialized                                , _, m: Ctl.Propose    ) => handlePropose(m)
    case (_ @ (_: State.Initialized| _:State.ProposalReceived), _, m: Ctl.Offer      ) => handleOffer(m)
    case (st: State.OfferReceived                             , _, m: Ctl.Request    ) => handleRequest(m, st)
    case (st: State.RequestReceived                           , _, m: Ctl.Issue      ) => handleIssue(m, st)

    case (st: State                                           , _, _: Ctl.Status     ) => handleStatus(st)
    case (st: State.PostInteractionStarted                    , _, m: Ctl.Reject     ) => handleReject(m, st)
    case (st: State                                           , _, m: Ctl            ) =>
      ctx.signal(SignalMsg.buildProblemReport(
        s"Unexpected '${IssueCredMsgFamily.msgType(m.getClass).msgName}' message in current state '${st.status}",
        unexpectedMessage
      ))
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = {
    case (_: State.Initialized, None | Some(Holder()), m: ProposeCred   )     => handleProposeCredReceived(m)

    case (_ @ (_: State.Initialized | _: State.ProposalSent),
                                None | Some(Issuer()), m: OfferCred     )     => handleOfferCredReceived(m)

    case (st: State.OfferSent,     Some(Holder()), m: RequestCred        )     => handleRequestCredReceived(m, st)

    case (_: State.RequestSent,   Some(Issuer()), m: IssueCred          )     => handleIssueCredReceived(m)

    case (st: State,              _,              m: ProblemReport      )     => handleProblemReport(m, st)

    case (_: State.IssueCredSent, _,              m: Ack                )     => handleAck(m)
  }

  def handleInit(m: Ctl.Init): Unit = {
    ctx.apply(Initialized(m.params.initParams.map(p => InitParam(p.name, p.value)).toSeq))
  }

  def handlePropose(m: Ctl.Propose): Unit = {
    val credPreviewEventObject = buildCredPreview(m.credential_values).toOption.map(buildEventCredPreview)
    val credProposed = CredProposed(m.cred_def_id, credPreviewEventObject, commentReq(m.comment))
    val proposedCred = ProposeCred(m.cred_def_id, buildCredPreview(m.credential_values).toOption, Option(credProposed.comment))
    ctx.apply(ProposalSent(ctx.getInFlight.sender.id_!, Option(credProposed)))
    ctx.send(proposedCred)
    ctx.signal(SignalMsg.Sent(proposedCred))
  }

  def handleOffer(m: Ctl.Offer): Unit = {
    ctx.wallet.createCredOffer(m.cred_def_id) match {
      case Success(credOffer) =>
        val credPreview = buildCredPreview(m.credential_values)
        val credPreviewEventObject = credPreview.toOption.map(buildEventCredPreview)
        val attachment = buildAttachment(Some("libindy-cred-offer-0"), payload=credOffer)
        val attachmentEventObject = toEvent(attachment)
        val credOffered = CredOffered(credPreviewEventObject, Seq(attachmentEventObject), commentReq(m.comment), m.price)
        val eventOffer = OfferSent(ctx.getInFlight.sender.id_!, Option(credOffered), m.auto_issue.getOrElse(false))
        ctx.apply(eventOffer)
        val offerCred = OfferCred(credPreview, Vector(attachment), Option(credOffered.comment), m.price)
        ctx.send(offerCred)
        ctx.signal(SignalMsg.Sent(offerCred))
      case Failure(_) =>
        //TODO: need to finalize error message based on different expected exception
        ctx.signal(
          SignalMsg.buildProblemReport(
            "unable to create credential offer",
            credentialOfferCreation
          )
        )
    }
  }


  def handleRequest(m: Ctl.Request, st: State.OfferReceived): Unit = {
    ctx.ledger.getCredDef(m.cred_def_id) match {
      case Success(GetCredDefResp(_, Some(cdj))) => sendCredRequest(m, st, DefaultMsgCodec.toJson(cdj))

      case Success(GetCredDefResp(_, None)) =>
        ctx.signal(SignalMsg.buildProblemReport(
          "cred def not found on ledger",
          ledgerAssetsUnavailable
        ))

      case Failure(_)   =>
        //TODO: need to finalize error message based on different expected exception
        ctx.signal(
          SignalMsg.buildProblemReport(
            s"unable to retrieve cred def from ledger (CredDefId: ${m.cred_def_id})",
            ledgerAssetsUnavailable
          )
        )
    }
  }

  def sendCredRequest(m: Ctl.Request, st: State.OfferReceived, credDefJson: String): Unit = {
    val credOfferJson = extractCredOfferJson(st.credOffer)
    ctx.wallet.createCredReq(m.cred_def_id, st.myPwDid, credDefJson, credOfferJson) match {
      case Success(credRequest) =>
        val attachment = buildAttachment(Some("libindy-cred-req-0"), payload=credRequest)
        val attachmentEventObject = toEvent(attachment)
        val credRequested = CredRequested(Seq(attachmentEventObject), commentReq(m.comment))
        ctx.apply(RequestSent(ctx.getInFlight.sender.id_!, Option(credRequested)))
        val rc = RequestCred(Vector(attachment), Option(credRequested.comment))
        ctx.send(rc)
        ctx.signal(SignalMsg.Sent(rc))
      case Failure(_) =>
        ctx.signal(
          SignalMsg.buildProblemReport(
            "unable to create credential request",
            credentialRequestCreation
          )
        )
    }
  }

  def handleIssue(m: Ctl.Issue, st: State.RequestReceived): Unit = {
    doIssueCredential(
      st.credOffer,
      st.credRequest,
      m.revRegistryId,
      m.comment,
      m.`~please_ack`
    )
  }

  def doIssueCredential(credOffer: OfferCred,
                        credRequest: RequestCred,
                        revRegistryId: Option[String]=None,
                        comment: Option[String]=Some(""),
                        `~please_ack`: Option[PleaseAck]=None): Unit = {
    val credOfferJson = extractCredOfferJson(credOffer)
    val credReqJson = extractCredReqJson(credRequest)
    val credValuesJson = buildCredValueJson(credOffer.credential_preview)
    ctx.wallet.createCred(credOfferJson, credReqJson, credValuesJson,
      revRegistryId.orNull, -1) match {
      case Success(cred) =>
        val attachment = buildAttachment(Some("libindy-cred-0"), payload=cred)
        val attachmentEventObject = toEvent(attachment)
        val credIssued = CredIssued(Seq(attachmentEventObject), commentReq(comment))
        ctx.apply(IssueCredSent(Option(credIssued)))
        val issueCred = IssueCred(Vector(attachment), Option(credIssued.comment), `~please_ack` = `~please_ack`)
        ctx.send(issueCred)
        ctx.signal(SignalMsg.Sent(issueCred))
      case Failure(_) =>
        //TODO: need to finalize error message based on different expected exception
        ctx.signal(
          SignalMsg.buildProblemReport(
            "cred issuance failed",
            credentialRequestCreation
          )
        )
    }
  }

  def buildCredValueJson(cp: CredPreview): String = {
    val credValues = cp.attributes.map { a =>
      a.name -> EncodedCredAttribute(a.value, CredValueEncoderV1_0.encodedValue(a.value))
    }.toMap
    DefaultMsgCodec.toJson(credValues)
  }


  def handleReject(m: Ctl.Reject, st: PostInteractionStarted): Unit = {
    ctx.apply(Rejected(m.comment))
    ctx.send(Msg.buildProblemReport(m.comment.getOrElse("credential rejected"), "rejection"))
  }

  def handleProblemReport(m: ProblemReport, st: State): Unit = {
    val reason = m.resolveDescription
    ctx.apply(ProblemReportReceived(reason))
    ctx.signal(SignalMsg.buildProblemReport(reason, m.tryDescription().code))
  }

  def handleAck(m: Ack): Unit = {
    ctx.signal(SignalMsg.Ack(m.status))
  }

  def handleStatus(st: State): Unit = {
    ctx.signal(StatusReport(st.status))
  }

  def handleProposeCredReceived(m: ProposeCred): Unit = {
    val credPreview = m.credential_proposal.map(buildEventCredPreview)
    val proposal = CredProposed(m.cred_def_id, credPreview, commentReq(m.comment))
    ctx.apply(ProposalReceived(ctx.getInFlight.sender.id_!, Option(proposal)))
    ctx.signal(SignalMsg.AcceptProposal(m))
  }

  def handleOfferCredReceived(m: OfferCred): Unit = {
    val credPreviewObject = buildEventCredPreview(m.credential_preview)
    val attachmentObject = m.`offers~attach`.map(toEvent)
    val offer = CredOffered(Option(credPreviewObject), attachmentObject, commentReq(m.comment), m.price)
    ctx.apply(OfferReceived(ctx.getInFlight.sender.id_!, Option(offer)))
    ctx.signal(SignalMsg.AcceptOffer(m))
  }

  def handleRequestCredReceived(m: RequestCred, st: State.OfferSent): Unit = {
    val req = CredRequested(m.`requests~attach`.map(toEvent), commentReq(m.comment))
    ctx.apply(RequestReceived(ctx.getInFlight.sender.id_!, Option(req)))
    if (st.autoIssue) {
      doIssueCredential(st.credOffer, buildRequestCred(Option(req)))
    } else {
      ctx.signal(SignalMsg.AcceptRequest(m))
    }
  }

  def handleIssueCredReceived(m: IssueCred): Unit = {
    val cred = CredIssued(m.`credentials~attach`.map(toEvent), commentReq(m.comment))
    ctx.apply(IssueCredReceived(Option(cred)))
    ctx.signal(SignalMsg.Received(m))
    if (m.`~please_ack`.isDefined) {
      ctx.send(Ack("OK"))
    }
  }


  //----------------

  override def applyEvent: ApplyEvent = {
    case (_: State.Uninitialized, _, e: Initialized) =>
      val paramMap = Map(e.params map { p => p.name -> p.value }: _*)
      (State.Initialized(
        paramMap.getOrElse(MY_PAIRWISE_DID, throw new RuntimeException(s"$MY_PAIRWISE_DID not found in init params")),
        paramMap.getOrElse(THEIR_PAIRWISE_DID, throw new RuntimeException(s"$THEIR_PAIRWISE_DID not found in init params"))),
        initialize(e.params))

    case (st: HasMyAndTheirDid, _, e: ProposalSent) =>
      val proposal: ProposeCred = buildProposedCred(e.proposal)
      ( Option(State.ProposalSent(st.myPwDid, st.theirPwDid, proposal)),  setRole(e.senderId, Holder()))

    case (st: HasMyAndTheirDid, _, e: ProposalReceived) =>
      val proposal: ProposeCred = buildProposedCred(e.proposal)
      ( Option(State.ProposalReceived(st.myPwDid, st.theirPwDid, proposal)),  setRole(e.senderId, Holder()))

    case (st: HasMyAndTheirDid, _, e: OfferSent) =>
      val offer: OfferCred = buildOfferCred(e.offer)
      ( Option(State.OfferSent(st.myPwDid, st.theirPwDid, offer, e.autoIssue)), setRole(e.senderId, Issuer()))

    case (st: HasMyAndTheirDid, _, e: OfferReceived) =>
      val offer: OfferCred = buildOfferCred(e.offer)
      ( Option(State.OfferReceived(st.myPwDid, st.theirPwDid, offer)), setRole(e.senderId, Issuer()))

    case (st: State.OfferReceived, _, e: RequestSent) =>
      val request: RequestCred = buildRequestCred(e.request)
      ( Option(State.RequestSent(st.myPwDid, st.theirPwDid, st.credOffer, request)), setRole(e.senderId, Holder()))

    case (st: State.OfferSent, _, e: RequestReceived) =>
      val request: RequestCred = buildRequestCred(e.request)
      ( Option(State.RequestReceived(st.myPwDid, st.theirPwDid, st.credOffer, request)), setRole(e.senderId, Issuer()))

    case (st: HasMyAndTheirDid, _, e: IssueCredSent) =>
      val issueCred: IssueCred = buildIssueCred(e.cred)
      State.IssueCredSent(st.myPwDid, st.theirPwDid, issueCred)

    case (st: HasMyAndTheirDid, _, e: IssueCredReceived) =>
      val issueCred: IssueCred = buildIssueCred(e.cred)
      State.IssueCredReceived(st.myPwDid, st.theirPwDid, issueCred)

    case (_: PostInteractionStarted, _, e: Rejected) => State.Rejected(e.comment)

    case (_: State, _, e: ProblemReportReceived)     => State.ProblemReported(e.description)

  }

  //helper methods

  def setRole(senderId: String, senderRole: Role): Roster[Role] = {
    if (ctx.getRoster.selfRole.isEmpty) {
      val otherRole = senderRole match {
        case Holder() => Issuer()
        case Issuer() => Holder()
      }
      ctx.getRoster.withAssignmentById(
        senderRole -> senderId,
        otherRole  -> ctx.getRoster.otherId(senderId)
      )
    } else ctx.getRoster
  }

  def extractCredOfferJson(offerCred: OfferCred): String = {
    val attachment = offerCred.`offers~attach`.head
    extractString(attachment)
  }

  def extractCredReqJson(requestCred: RequestCred): String = {
    val attachment = requestCred.`requests~attach`.head
    extractString(attachment)
  }

  def getCredentialDataFromMessage(credentialValues: Map[String, String]): String = {
    val jsonObject: JSONObject = new JSONObject()
    for ((key, value) <- credentialValues) {
      jsonObject.put(key, value)
    }
    jsonObject.toString()
  }

  def buildEventCredPreview(cp: CredPreview): CredPreviewObject = {
    CredPreviewObject(cp.`@type`,
      cp.attributes.map(a => CredPreviewAttr(a.name, a.value, a.`mime-type`)))
  }

  def buildCredPreview(cpo: CredPreviewObject): CredPreview = {
    val cpa = cpo.attributes.map { a =>
      CredPreviewAttribute(a.name, a.value, a.mimeType)
    }
    CredPreview(cpo.`type`, cpa.toVector)
  }

  def buildCredPreview(credValues: Map[String, String]): CredPreview = {
    val msgType = IssueCredentialProtoDef.msgFamily.msgType("credential-preview")
    val cpa = credValues.map { case (name, value) =>
      CredPreviewAttribute(name, value)
    }
    CredPreview(MsgFamily.typeStrFromMsgType(msgType), cpa.toVector)
  }

  def toEvent(a: AttachmentDescriptor): AttachmentObject = {
    AttachmentObject(a.`@id`.get, a.`mime-type`.get, a.data.base64)
  }

  def fromEvent(ao: AttachmentObject): AttachmentDescriptor = {
    AttachmentDescriptor(Some(ao.id), Some(ao.mimeType), Base64(ao.dataBase64))
  }

  def buildProposedCred(proposal: Option[CredProposed]): ProposeCred = {
    proposal match {
      case Some(p) =>
        val credPreview = p.credentialProposal.map { cp =>
          val credAttrs = cp.attributes.map(a => CredPreviewAttribute(a.name, a.value, a.mimeType))
          CredPreview(cp.`type`, credAttrs.toVector)
        }
        ProposeCred(p.credDefId, credPreview, Option(p.comment))
      case None    => throw new NotImplementedError()
    }
  }

  def buildOfferCred(offer: Option[CredOffered]): OfferCred = {
    offer match {
      case Some(o) =>
        val cp = o.credentialPreview.map(buildCredPreview).getOrElse(
          throw new RuntimeException("cred preview is required"))
        OfferCred(cp, o.offersAttach.map(fromEvent).toVector, Option(o.comment), o.price)
      case None    => throw new NotImplementedError()
    }
  }

  def buildRequestCred(request: Option[CredRequested]): RequestCred = {
    request match {
      case Some(r) => RequestCred(r.requestAttach.map(fromEvent).toVector, Option(r.comment))
      case None    => throw new NotImplementedError()
    }
  }

  def buildIssueCred(cred: Option[CredIssued]): IssueCred = {
    cred match {
      case Some(c) => IssueCred(c.credAttach.map(fromEvent).toVector, Option(c.comment))
      case None    => throw new NotImplementedError()
    }
  }

  def commentReq(comment: Option[String]): String = {
    comment.getOrElse("")
  }

  def initialize(params: Seq[InitParam]): Roster[Role] = {
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
  }

}

case class EncodedCredAttribute(raw: String, encoded: String)