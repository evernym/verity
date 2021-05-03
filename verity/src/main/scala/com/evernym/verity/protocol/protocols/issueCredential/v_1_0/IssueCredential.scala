package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.actor.wallet.{CredCreated, CredOfferCreated, CredReqCreated}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.Constants.UNKNOWN_OTHER_ID
import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.ledger.GetCredDefResp
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.conventions.CredValueEncoderV1_0
import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor._
import com.evernym.verity.protocol.didcomm.decorators.{AttachmentDescriptor, Base64, PleaseAck}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.ShortenInvite
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{OfferCred, _}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.ProblemReportCodes._
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Role.{Holder, Issuer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.SignalMsg.StatusReport
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.State.{HasMyAndTheirDid, PostInteractionStarted}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.InviteUtil
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.prepareInviteUrl
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProtocolHelpers
import com.evernym.verity.urlshortener.{UrlShortened, UrlShorteningFailed}
import com.evernym.verity.util.{MsgIdProvider, OptionUtil}
import org.json.JSONObject

import scala.util.{Failure, Success, Try}

//protocol document:
// https://github.com/hyperledger/aries-rfcs/blob/527849e/features/0036-issue-credential/README.md

class IssueCredential(implicit val ctx: ProtocolContextApi[IssueCredential, Role, Msg, Event, State, String])
  extends Protocol[IssueCredential, Role, Msg, Event, State, String](IssueCredentialProtoDef)
    with ProtocolHelpers[IssueCredential, Role, Msg, Event, State, String] {
  import IssueCredential._

  override def handleControl: Control ?=> Any = statefulHandleControl {
    case (State.Uninitialized()                               , _, m: Ctl.Init           ) => handleInit(m)
    case (_: State.Initialized                                , _, m: Ctl.AttachedOffer  ) => handleAttachedOffer(m)
    case (_: State.Initialized                                , _, m: Ctl.Propose        ) => handlePropose(m)
    case (st: State.Initialized                               , _, m: Ctl.Offer          ) => handleInitialOffer(st, m)
    case (_:State.ProposalReceived                            , _, m: Ctl.Offer          ) => handleOffer(m)
    case (st: State.OfferReceived                             , _, m: Ctl.Request        ) => handleRequest(m, st)
    case (st: State.RequestReceived                           , _, m: Ctl.Issue          ) => handleIssue(m, st)

    case (st: State                                           , _, _: Ctl.Status         ) => handleStatus(st)
    case (st: State.PostInteractionStarted                    , _, m: Ctl.Reject         ) => handleReject(m, st)
    case (st: State                                           , _, m: Ctl                ) =>
      ctx.signal(SignalMsg.buildProblemReport(
        s"Unexpected '${IssueCredMsgFamily.msgType(m.getClass).msgName}' message in current state '${st.status}",
        unexpectedMessage
      ))
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = {
    case (_: State.Initialized,   _, m: ProposeCred)   => handleProposeCredReceived(m, ctx.getInFlight.sender.id_!)

    case (_ @ (_: State.Initialized | _: State.ProposalSent),
                                  _, m: OfferCred)     => handleOfferCredReceived(m, ctx.getInFlight.sender.id_!)

    case (st: State.OfferSent,    _, m: RequestCred)   => handleRequestCredReceived(m, st, ctx.getInFlight.sender.id_!)

    case (_: State.RequestSent,   _, m: IssueCred)     => handleIssueCredReceived(m)

    case (st: State,              _, m: ProblemReport) => handleProblemReport(m, st)

    case (_: State.CredSent, _, m: Ack)           => handleAck(m)
  }

  def handleInit(m: Ctl.Init): Unit = {
    ctx.apply(buildInitialized(m.params))
  }

  def handleAttachedOffer(m: Ctl.AttachedOffer): Unit = {
    handleOfferCredReceived(m.offer, ctx.getRoster.otherId())
  }

  def handlePropose(m: Ctl.Propose): Unit = {
    val credPreviewEventObject = buildCredPreview(m.credential_values).toOption.map(buildEventCredPreview)
    val credProposed = CredProposed(m.cred_def_id, credPreviewEventObject, commentReq(m.comment))
    val proposedCred = ProposeCred(m.cred_def_id, buildCredPreview(m.credential_values).toOption, Option(credProposed.comment))
    ctx.apply(ProposalSent(ctx.getInFlight.sender.id_!, Option(credProposed)))
    ctx.send(proposedCred)
    ctx.signal(SignalMsg.Sent(proposedCred))
  }

  def sendInvite(offer: OfferCred, s: State.Initialized): Unit = {
    buildOobInvite(offer, s) {
      case Success(invite) =>
        ctx.urlShortening.shorten(invite.inviteURL) {
          case Success(us: UrlShortened) => ctx.signal(SignalMsg.Invitation(invite.inviteURL, Option(us.shortUrl), invite.invitationId))
          case Success(usf: UrlShorteningFailed) =>
            ctx.signal(SignalMsg.buildProblemReport(usf.errorMsg, usf.errorCode))
            ctx.apply(ProblemReportReceived("Shortening failed"))
          case _ =>
            ctx.signal(SignalMsg.buildProblemReport("Shortening failed", shorteningFailed))
            ctx.apply(ProblemReportReceived("Shortening failed"))
        }
      case Failure(e) =>
        ctx.logger.warn(s"Unable to create out-of-band invitation -- ${e.getMessage}")
        SignalMsg.buildProblemReport(
          "unable to create out-of-band invitation",
          credentialOfferCreation
        )
    }
  }

  def handleInitialOffer(s: State.Initialized, m: Ctl.Offer): Unit = {
    buildOffer(m) {
      case Success((event, offer)) =>
        ctx.apply(event)
        if(!m.by_invitation.getOrElse(false)) {
          ctx.send(offer)
          ctx.signal(SignalMsg.Sent(offer))
        }
        else sendInvite(offer, s)
      case Failure(_) =>
        ctx.signal(
          SignalMsg.buildProblemReport(
            "unable to create credential offer",
            credentialOfferCreation
          )
        )
    }
  }

  def handleOffer(m: Ctl.Offer): Unit = {
    buildOffer(m) {
      case Success((event, offer)) =>
        ctx.apply(event)
        ctx.send(offer)
        ctx.signal(SignalMsg.Sent(offer))
      case Failure(_) =>
        ctx.signal(
          SignalMsg.buildProblemReport(
            "unable to create credential offer",
            credentialOfferCreation
          )
        )
    }
  }


  def handleRequest(m: Ctl.Request, st: State.OfferReceived): Unit = {
    ctx.ledger.getCredDef(m.cred_def_id) {
      case Success(GetCredDefResp(_, Some(cdj))) => sendCredRequest(m, st, DefaultMsgCodec.toJson(cdj))

      case Success(GetCredDefResp(_, None)) =>
        ctx.signal(SignalMsg.buildProblemReport(
          "cred def not found on ledger",
          ledgerAssetsUnavailable
        ))

      case Failure(_)   =>
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
    ctx.wallet.createCredReq(m.cred_def_id, st.myPwDid, credDefJson, credOfferJson) {
      case Success(credRequest: CredReqCreated) =>
        val attachment = buildAttachment(Some("libindy-cred-req-0"), payload=credRequest.credReqJson)
        val attachmentEventObject = toEvent(attachment)
        val credRequested = CredRequested(Seq(attachmentEventObject), commentReq(m.comment))
        //TODO: store cred req metadata to be used later on
        // (at least libindy Anoncreds.proverStoreCredential api expects it)?
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
    val credValuesJson = IssueCredential.buildCredValueJson(credOffer.credential_preview)
    ctx.wallet.createCred(credOfferJson, credReqJson, credValuesJson, revRegistryId.orNull, -1) {
      case Success(createdCred: CredCreated) =>
        val attachment = buildAttachment(Some("libindy-cred-0"), payload=createdCred.cred)
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

  def handleProposeCredReceived(m: ProposeCred, senderId: String): Unit = {
    val credPreview = m.credential_proposal.map(buildEventCredPreview)
    val proposal = CredProposed(m.cred_def_id, credPreview, commentReq(m.comment))
    ctx.apply(ProposalReceived(senderId, Option(proposal)))
    ctx.signal(SignalMsg.AcceptProposal(m))
  }

  def handleOfferCredReceived(m: OfferCred, senderId: String): Unit = {
    val credPreviewObject = buildEventCredPreview(m.credential_preview)
    val attachmentObject = m.`offers~attach`.map(toEvent)
    val offer = CredOffered(Option(credPreviewObject), attachmentObject, commentReq(m.comment), m.price)
    ctx.apply(OfferReceived(senderId, Option(offer)))
    ctx.signal(SignalMsg.AcceptOffer(m))
  }

  def handleRequestCredReceived(m: RequestCred, st: State.OfferSent, senderId: String): Unit = {
    val req = CredRequested(m.`requests~attach`.map(toEvent), commentReq(m.comment))
    ctx.apply(RequestReceived(senderId, Option(req)))
    if (st.autoIssue) {
      doIssueCredential(st.credOffer, buildRequestCred(Option(req)))
    } else {
      ctx.signal(SignalMsg.AcceptRequest(m))
    }
  }

  def handleIssueCredReceived(m: IssueCred): Unit = {
    val cred = CredIssued(m.`credentials~attach`.map(toEvent), commentReq(m.comment))
    //TODO: we purposefully are not storing the received credential in the wallet
    // as we are not sure yet if that is the right way to proceed or we want it to
    // store it in persistent store and make it available to the state for further uses
    ctx.apply(IssueCredReceived(Option(cred)))
    ctx.signal(SignalMsg.Received(m))
    if (m.`~please_ack`.isDefined) {
      ctx.send(Ack("OK"))
    }
  }


  //----------------

  override def applyEvent: ApplyEvent = {
    case (_: State.Uninitialized, _, e: Initialized) =>
      val params = e.params
        .filterNot(p => p.name == OTHER_ID && p.value == UNKNOWN_OTHER_ID)

      val paramMap = Map(params map { p => p.name -> p.value }: _*)

      (
        State.Initialized(
          paramMap.getOrElse(MY_PAIRWISE_DID, throw new RuntimeException(s"$MY_PAIRWISE_DID not found in init params")),
          paramMap.get(THEIR_PAIRWISE_DID).flatMap(OptionUtil.blankOption),
          paramMap.get(NAME),
          paramMap.get(LOGO_URL),
          paramMap.get(AGENCY_DID_VER_KEY),
          paramMap.get(MY_PUBLIC_DID).flatMap(OptionUtil.blankOption),
        ),
        initialize(params)
      )

    case (st: HasMyAndTheirDid, _, e: ProposalSent) =>
      val proposal: ProposeCred = buildProposedCred(e.proposal)
      ( Option(State.ProposalSent(st.myPwDid, st.theirPwDid, proposal)),  setSenderRole(e.senderId, Holder(), ctx.getRoster))

    case (st: HasMyAndTheirDid, _, e: ProposalReceived) =>
      val proposal: ProposeCred = buildProposedCred(e.proposal)
      ( Option(State.ProposalReceived(st.myPwDid, st.theirPwDid, proposal)),  setSenderRole(e.senderId, Holder(), ctx.getRoster))

    case (st: HasMyAndTheirDid, _, e: OfferSent) =>
      val offer: OfferCred = buildOfferCred(e.offer)
      ( Option(State.OfferSent(st.myPwDid, st.theirPwDid, offer, e.autoIssue)), setSenderRole(e.senderId, Issuer(), ctx.getRoster))

    case (st: HasMyAndTheirDid, _, e: OfferReceived) =>
      val offer: OfferCred = buildOfferCred(e.offer)
      ( Option(State.OfferReceived(st.myPwDid, st.theirPwDid, offer)), setSenderRole(e.senderId, Issuer(), ctx.getRoster))

    case (st: State.OfferReceived, _, e: RequestSent) =>
      val request: RequestCred = buildRequestCred(e.request)
      ( Option(State.RequestSent(st.myPwDid, st.theirPwDid, st.credOffer, request)), setSenderRole(e.senderId, Holder(), ctx.getRoster))

    case (st: State.OfferSent, _, e: RequestReceived) =>
      val request: RequestCred = buildRequestCred(e.request)
      ( Option(State.RequestReceived(st.myPwDid, st.theirPwDid, st.credOffer, request)), setSenderRole(e.senderId, Issuer(), ctx.getRoster))

    case (st: HasMyAndTheirDid, _, e: IssueCredSent) =>
      val issueCred: IssueCred = buildIssueCred(e.cred)
      State.CredSent(st.myPwDid, st.theirPwDid, issueCred)

    case (st: HasMyAndTheirDid, _, e: IssueCredReceived) =>
      val issueCred: IssueCred = buildIssueCred(e.cred)
      State.CredReceived(st.myPwDid, st.theirPwDid, issueCred)

    case (_: PostInteractionStarted, _, e: Rejected) => State.Rejected(e.comment)

    case (_: State, _, e: ProblemReportReceived)     => State.ProblemReported(e.description)

  }

  //helper methods

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

  def buildOffer(m: Ctl.Offer)(handler: Try[(OfferSent, OfferCred)] => Unit): Unit = {
    ctx.wallet.createCredOffer(m.cred_def_id) {
      case Success(coc: CredOfferCreated) =>
        val credPreview = buildCredPreview(m.credential_values)
        val credPreviewEventObject = credPreview.toOption.map(buildEventCredPreview)
        val attachment = buildAttachment(Some("libindy-cred-offer-0"), payload = coc.offer)
        val attachmentEventObject = toEvent(attachment)

        val credOffered = CredOffered(
          credPreviewEventObject,
          Seq(attachmentEventObject),
          commentReq(m.comment),
          m.price
        )
        val eventOffer = OfferSent(
          ctx.getInFlight.sender.id_!,
          Option(credOffered),
          m.auto_issue.getOrElse(false)
        )
        val offerCred = OfferCred(
          credPreview,
          Vector(attachment),
          Option(credOffered.comment),
          m.price
        )
        handler(Try(eventOffer -> offerCred))
      case Failure(e) => handler(Failure(e))
    }
  }

  def buildEventCredPreview(cp: CredPreview): CredPreviewObject = {
    CredPreviewObject(cp.`@type`,
      cp.attributes.map(a => CredPreviewAttr(a.name, a.value, a.`mime-type`)))
  }

  def buildCredPreviewFromObject(cpo: CredPreviewObject): CredPreview = {
    val cpa = cpo.attributes.map { a =>
      CredPreviewAttribute(a.name, a.value, a.mimeType)
    }
    CredPreview(cpo.`type`, cpa.toVector)
  }

  def toEvent(a: AttachmentDescriptor): AttachmentObject = {
    AttachmentObject(a.`@id`.getOrElse(""), a.`mime-type`.getOrElse(""), a.data.base64)
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
        val cp = o.credentialPreview.map(buildCredPreviewFromObject).getOrElse(
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

  def buildOobInvite(offer: OfferCred, s: State.Initialized)
                    (handler: Try[ShortenInvite] => Unit): Unit = {
    InviteUtil.withServiced(s.agencyVerkey, ctx) {
      case Success(service) =>
        val offerAttachment = buildProtocolMsgAttachment(
          MsgIdProvider.getNewMsgId,
          ctx.threadId_!,
          IssueCredentialProtoDef.msgFamily,
          offer
        )

        val invite = InviteUtil.buildInviteWithThreadedId(
          definition.msgFamily.protoRef,
          ctx.getRoster.selfId_!,
          ctx.`threadId_!`,
          s.agentName,
          s.logoUrl,
          s.publicDid,
          service,
          offerAttachment
        )

        handler(Success(
          ShortenInvite(invite.`@id`, prepareInviteUrl(invite, ctx.serviceEndpoint))
        ))
      case Failure(ex) =>
        handler(Failure(ex))
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

object IssueCredential {

  def setSenderRole(senderId: String, senderRole: Role, roster: Roster[Role]): Roster[Role] = {
    val r = roster.withParticipant(senderId)

    if (r.roleForId(senderId).isEmpty) {
      val otherRole = senderRole match {
        case Holder() => Issuer()
        case Issuer() => Holder()
      }

      val newRoster = r.withAssignmentById(senderRole -> senderId)
      if(newRoster.hasOther) {
        newRoster.withAssignmentById(
          otherRole  -> r.otherId(senderId)
        )
      } else newRoster
    } else r
  }

  def buildInitialized(params: Parameters): Initialized = {
    Initialized(
      params
        .initParams
        .filterNot(p => p.name == OTHER_ID && p.value == UNKNOWN_OTHER_ID)
        .map(p => InitParam(p.name, p.value))
        .toSeq
    )
  }

  def buildCredPreview(credValues: Map[String, String]): CredPreview = {
    val msgType = IssueCredentialProtoDef.msgFamily.msgType("credential-preview")
    val cpa = credValues.map { case (name, value) =>
      CredPreviewAttribute(name, value)
    }
    CredPreview(MsgFamily.typeStrFromMsgType(msgType), cpa.toVector)
  }

  def buildCredValueJson(cp: CredPreview): String = {
    val credValues = cp.attributes.map { a =>
      a.name -> EncodedCredAttribute(a.value, CredValueEncoderV1_0.encodedValue(a.value))
    }.toMap
    DefaultMsgCodec.toJson(credValues)
  }
}
