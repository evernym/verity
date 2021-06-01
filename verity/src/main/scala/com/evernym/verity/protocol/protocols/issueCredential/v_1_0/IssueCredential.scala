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
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.StoredSegment
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.ShortenInvite
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentId
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg._
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.ProblemReportCodes._
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Role.{Holder, Issuer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.State.{HasMyAndTheirDid, PostInteractionStarted}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.{State => S}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.legacy.IssueCredentialLegacy
import com.evernym.verity.protocol.protocols.outofband.v_1_0.InviteUtil
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.prepareInviteUrl
import com.evernym.verity.urlshortener.{UrlShortened, UrlShorteningFailed}
import com.evernym.verity.util.{MsgIdProvider, OptionUtil}
import org.json.JSONObject

import scala.util.{Failure, Success, Try}

//protocol document:
// https://github.com/hyperledger/aries-rfcs/blob/527849e/features/0036-issue-credential/README.md

class IssueCredential(implicit val ctx: ProtocolContextApi[IssueCredential, Role, ProtoMsg, Event, State, String])
  extends Protocol[IssueCredential, Role, ProtoMsg, Event, State, String](IssueCredentialProtoDef)
    with ProtocolHelpers[IssueCredential, Role, ProtoMsg, Event, State, String]
    with IssueCredentialHelpers
    with IssueCredentialLegacy {
  import IssueCredential._

  override def handleControl: Control ?=> Any =
    handleMainControl orElse
      legacyControl orElse
      handleInvalidControl

  override def handleProtoMsg: (State, Option[Role], ProtoMsg) ?=> Any =
    handleMainProtoMsg orElse
      legacyProtoMsg orElse
      handleInvalidProtoMsg

  override def applyEvent: ApplyEvent = mainApplyEvent orElse legacyApplyEvent

  def handleMainControl: Control ?=> Any = statefulHandleControl {
    case (S.Uninitialized()            , _, m: Ctl.Init           ) => handleInit(m)
    case (_: S.Initialized             , _, m: Ctl.AttachedOffer  ) => handleAttachedOffer(m)
    case (_: S.Initialized             , _, m: Ctl.Propose        ) => handlePropose(m)
    case (s: S.Initialized             , _, m: Ctl.Offer          ) => handleInitialOffer(s, m)
    case (_: S.ProposalReceived        , _, m: Ctl.Offer          ) => handleOffer(m)
    case (s: S.OfferReceived           , _, m: Ctl.Request        ) => handleRequest(s.credOfferRef, m, s.myPwDid)
    case (s: S.RequestReceived         , _, m: Ctl.Issue          ) => handleIssue(m, s)
    case (s: S.PostInteractionStarted  , _, m: Ctl.Reject         ) => handleReject(m, s)
    case (s: S                         , _, _: Ctl.Status         ) => handleStatus(s)
  }

  def handleInvalidControl: Control ?=> Any = statefulHandleControl {
    case (s: S                        , _, m: CtlMsg            ) => invalidControlState(s, m)
  }

  def handleMainProtoMsg: (S, Option[Role], ProtoMsg) ?=> Any = {
    case (_: S.Initialized,                           _, m: ProposeCred)   => handleProposeCredReceived(m, senderId_!())
    case (_ @ (_: S.Initialized | _: S.ProposalSent), _, m: OfferCred)     => handleOfferCredReceived(m, senderId_!())
    case (s: S.OfferSent,                             _, m: RequestCred)   => handleRequestCredReceived(m, s)
    case (_: S.RequestSent,                           _, m: IssueCred)     => handleIssueCredReceived(m)
    case (_: S.CredSent,                              _, m: Ack)           => handleAck(m)
    case (s: S,                                       _, m: ProblemReport) => handleProblemReport(m, s)
  }

  def handleInvalidProtoMsg: (S, Option[Role], ProtoMsg) ?=> Any = {
    case (_: S,                                       _, m: ProtoMsg)      => invalidMessageState(m)
  }

  def mainApplyEvent: ApplyEvent = {
    case (_: S.Uninitialized, _, e: Initialized) =>
      val params = e.params
        .filterNot(p => p.name == OTHER_ID && p.value == UNKNOWN_OTHER_ID)

      val paramMap = Map(params map { p => p.name -> p.value }: _*)

      (
        S.Initialized(
          paramMap.getOrElse(MY_PAIRWISE_DID, throw new RuntimeException(s"$MY_PAIRWISE_DID not found in init params")),
          paramMap.get(THEIR_PAIRWISE_DID).flatMap(OptionUtil.blankOption),
          paramMap.get(NAME),
          paramMap.get(LOGO_URL),
          paramMap.get(AGENCY_DID_VER_KEY),
          paramMap.get(MY_PUBLIC_DID).flatMap(OptionUtil.blankOption),
        ),
        initialize(params)
      )

    case (s: HasMyAndTheirDid, _, e: ProposalSent) =>
      (
        Option(S.ProposalSent(s.myPwDid, s.theirPwDid, e.proposalRef)),
        setSenderRole(e.senderId, Holder(), ctx.getRoster)
      )
    case (s: HasMyAndTheirDid, _, e: ProposalReceived) =>
      (
        Option(S.ProposalReceived(s.myPwDid, s.theirPwDid, e.proposalRef)),
        setSenderRole(e.senderId, Holder(), ctx.getRoster)
      )
    case (s: HasMyAndTheirDid, _, e: OfferSent) =>
      (
        Option(S.OfferSent(s.myPwDid, s.theirPwDid, e.offerRef, e.autoIssue)),
        setSenderRole(e.senderId, Issuer(), ctx.getRoster)
      )
    case (s: HasMyAndTheirDid, _, e: OfferReceived) =>
      (
        Option(S.OfferReceived(s.myPwDid, s.theirPwDid, e.offerRef)),
        setSenderRole(e.senderId, Issuer(), ctx.getRoster)
      )
    case (s: S.OfferReceived, _, e: RequestSent) =>
      (
        Option(S.RequestSent(s.myPwDid, s.theirPwDid, e.requestRef)),
        setSenderRole(e.senderId, Holder(), ctx.getRoster)
      )
    case (s: S.OfferSent, _, e: RequestReceived) =>
      (
        Option(S.RequestReceived(s.myPwDid, s.theirPwDid, s.credOfferRef, e.requestRef)),
        setSenderRole(e.senderId, Issuer(), ctx.getRoster)
      )
    case (s: HasMyAndTheirDid, _, e: IssueCredSent) =>
      S.CredSent(s.myPwDid, s.theirPwDid, e.credRef)
    case (s: HasMyAndTheirDid, _, e: IssueCredReceived) =>
      S.CredReceived(s.myPwDid, s.theirPwDid, e.credRef)
    case (_: PostInteractionStarted, _, e: Rejected) => S.Rejected(e.comment)
    case (_: S, _, e: ProblemReportReceived)     => S.ProblemReported(e.description)
  }

  def initialize(params: Seq[InitParam]): Roster[Role] = {
    ctx.updatedRoster(params.map(p => InitParamBase(p.name, p.value)))
  }
}

case class EncodedCredAttribute(raw: String, encoded: String)

object IssueCredential {

  def expiredDataRetentionMsg(msgType: String) = s"$msgType - data expired because of retention policy"
  def toAttachmentObject(a: AttachmentDescriptor): AttachmentObject = {
    AttachmentObject(a.`@id`.getOrElse(""), a.`mime-type`.getOrElse(""), a.data.base64)
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

trait IssueCredentialHelpers
  extends Protocol[IssueCredential, Role, ProtoMsg, Event, S, String]
    with ProtocolHelpers[IssueCredential, Role, ProtoMsg, Event, S, String] {

  import IssueCredential._

  override type Context = ProtocolContextApi[IssueCredential, Role, ProtoMsg, Event, S, String]

  implicit val ctx: Context

  def invalidMessageState(invalidMsg: ProtoMsg)
                         (implicit ctx: Context): Unit = {
    val msgName: String = invalidMsg.getClass.getSimpleName
    val errorMsg = s"Invalid '$msgName' message in current state"
    ctx.send(
      Msg.buildProblemReport(errorMsg, invalidMessageStateError)
    )
  }

  def invalidControlState(curState: State, invalidMsg: CtlMsg)
                         (implicit ctx: Context): Unit = {
    val msgName: String = IssueCredMsgFamily.msgType(invalidMsg.getClass).msgName
    val stateName: String = curState.getClass.getSimpleName
    val errorMsg = s"Unexpected '$msgName' message in current state '$stateName"
    ctx.signal(
      Sig.buildProblemReport(errorMsg, unexpectedMessage)
    )
  }

  def senderId_!(): ParticipantId = ctx.getInFlight.sender.id_!

  def handleInit(m: Ctl.Init): Unit = ctx.apply(buildInitialized(m.params))

  def handleAttachedOffer(m: Ctl.AttachedOffer): Unit = {
    handleOfferCredReceived(m.offer, ctx.getRoster.otherId())
  }

  def handlePropose(m: Ctl.Propose): Unit = {
    val credPreview = buildCredPreview(m.credential_values).toOption
    val credPreviewEventObject = credPreview.map(_.toCredPreviewObject)
    val credProposed = CredProposed(m.cred_def_id, credPreviewEventObject, commentReq(m.comment))
    val proposedCred = ProposeCred(m.cred_def_id, credPreview, Option(credProposed.comment))

    ctx.storeSegment(segment=credProposed) {
      case Success(s: StoredSegment) =>
        ctx.apply(ProposalSent(senderId_!(), s.segmentKey))
        ctx.send(proposedCred)
        ctx.signal(Sig.Sent(proposedCred))
      case Failure(e) =>
        ctx.logger.warn(s"could not store segment IssueCredential: ${e.getMessage}")
        ctx.send(Msg.buildProblemReport(s"could not store segment: $e", ""))
    }
  }

  def sendInvite(offer: OfferCred, s: S.Initialized): Unit = {
    buildOobInvite(offer, s) {
      case Success(invite) =>
        ctx.urlShortening.shorten(invite.inviteURL) {
          case Success(us: UrlShortened) => ctx.signal(Sig.Invitation(invite.inviteURL, Option(us.shortUrl), invite.invitationId))
          case Success(usf: UrlShorteningFailed) =>
            ctx.signal(Sig.buildProblemReport(usf.errorMsg, usf.errorCode))
            ctx.apply(ProblemReportReceived("Shortening failed"))
          case _ =>
            ctx.signal(Sig.buildProblemReport("Shortening failed", shorteningFailed))
            ctx.apply(ProblemReportReceived("Shortening failed"))
        }
      case Failure(e) =>
        ctx.logger.warn(s"Unable to create out-of-band invitation -- ${e.getMessage}")
        Sig.buildProblemReport(
          "unable to create out-of-band invitation",
          credentialOfferCreation
        )
    }
  }

  def handleInitialOffer(s: S.Initialized, m: Ctl.Offer): Unit = {
    buildOffer(m) {
      case Success((credOffered: CredOffered, offer: OfferCred)) =>
        ctx.storeSegment(segment=credOffered) {
          case Success(stored) =>
            ctx.apply(OfferSent(
              senderId_!(),
              stored.segmentKey,
              m.auto_issue.getOrElse(false)
            ))

            if(!m.by_invitation.getOrElse(false)) {
              ctx.send(offer)
              ctx.signal(Sig.Sent(offer))
            }
            else sendInvite(offer, s)
          case Failure(e) =>
            ctx.signal(
              Sig.buildProblemReport(
                s"unable to create credential offer - store segment error: $e",
                credentialOfferCreation
              ))
        }
      case Failure(_) =>
        ctx.signal(
          Sig.buildProblemReport(
            "unable to create credential offer",
            credentialOfferCreation
          )
        )
    }
  }

  // handle offer after proposal.
  def handleOffer(m: Ctl.Offer): Unit = {
    buildOffer(m) {
      case Success((credOffered: CredOffered, offer: OfferCred)) =>
        ctx.storeSegment(segment=credOffered) {
          case Success(s) =>
            ctx.apply(OfferSent(
              senderId_!(),
              s.segmentKey,
              m.auto_issue.getOrElse(false)
            ))
            ctx.send(offer)
            ctx.signal(Sig.Sent(offer))
          case Failure(e) =>
            ctx.signal(
              Sig.buildProblemReport(
                s"unable to create credential offer - store segment error: $e",
                credentialOfferCreation
              ))
        }
      case Failure(_) =>
        ctx.signal(
          Sig.buildProblemReport(
            "unable to create credential offer",
            credentialOfferCreation
          )
        )
    }
  }

  def handleRequest(credOfferRef: SegmentId, m: Ctl.Request, myPwDid: DID): Unit = {
    ctx.withSegment[CredOffered](credOfferRef) {
      case Success(o) if o.isDefined => handleRequest(m, myPwDid, buildOfferCred(o))
      case Success(None) => expiredSegment("Credential Offer")
      case Failure(e) => segmentFailure(e, ctx.getState)
    }
  }

  def handleRequest(m: Ctl.Request, myPwDid: DID, credOffer: OfferCred): Unit = {
    ctx.ledger.getCredDef(m.cred_def_id) {
      case Success(GetCredDefResp(_, Some(cdj))) => sendCredRequest(m, myPwDid, credOffer, DefaultMsgCodec.toJson(cdj))

      case Success(GetCredDefResp(_, None)) =>
        ctx.signal(Sig.buildProblemReport(
          "cred def not found on ledger",
          ledgerAssetsUnavailable
        ))

      case Failure(_)   =>
        ctx.signal(
          Sig.buildProblemReport(
            s"unable to retrieve cred def from ledger (CredDefId: ${m.cred_def_id})",
            ledgerAssetsUnavailable
          )
        )
    }
  }

  def sendCredRequest(m: Ctl.Request, myPwDid: DID, credOffer: OfferCred, credDefJson: String): Unit = {
    val credOfferJson = extractCredOfferJson(credOffer)
    ctx.wallet.createCredReq(m.cred_def_id, myPwDid, credDefJson, credOfferJson) {
      case Success(credRequest: CredReqCreated) =>
        val attachment = buildAttachment(Some("libindy-cred-req-0"), payload=credRequest.credReqJson)
        val attachmentEventObject = toAttachmentObject(attachment)
        val credRequested = CredRequested(Seq(attachmentEventObject), commentReq(m.comment))
        //TODO: store cred req metadata to be used later on
        // (at least libindy Anoncreds.proverStoreCredential api expects it)?

        ctx.storeSegment(segment=credRequested) {
          case Success(s) =>
            ctx.apply(RequestSent(senderId_!(), s.segmentKey))
            val rc = RequestCred(Vector(attachment), Option(credRequested.comment))
            ctx.send(rc)
            ctx.signal(Sig.Sent(rc))
          case Failure(e) =>
            ctx.signal(
              Sig.buildProblemReport(
                s"unable to create credential request - store segment error: $e",
                credentialRequestCreation
              )
            )
        }
      case Failure(_) =>
        ctx.signal(
          Sig.buildProblemReport(
            "unable to create credential request",
            credentialRequestCreation
          )
        )
    }
  }

  def handleIssue(m: Ctl.Issue, s: S.RequestReceived): Unit = {
    ctx.withSegment[CredOffered](s.credOfferRef) {
      case Success(o) if o.isDefined =>
        ctx.withSegment[CredRequested](s.credRequestRef) {
          case Success(r) if r.isDefined => handleIssue(m, buildOfferCred(o), buildRequestCred(r))
          case Success(None) => expiredSegment("Cred Request")
          case Failure(e) => segmentFailure(e, s)
        }
      case Success(None) => expiredSegment("Cred Offer")
      case Failure(e) => segmentFailure(e, s)
    }

  }

  def handleIssue(m: Ctl.Issue, credOffer: OfferCred, credRequest: RequestCred): Unit = {
    doIssueCredential(
      credOffer,
      credRequest,
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
        val attachmentEventObject = toAttachmentObject(attachment)
        val credIssued = CredIssued(Seq(attachmentEventObject), commentReq(comment))

        ctx.storeSegment(segment = credIssued) {
          case Success(s) =>
            ctx.apply(IssueCredSent(s.segmentKey))
            val issueCred = IssueCred(Vector(attachment), Option(credIssued.comment), `~please_ack` = `~please_ack`)
            ctx.send(issueCred)
            ctx.signal(Sig.Sent(issueCred))
          case Failure(e) =>
            ctx.signal(
              Sig.buildProblemReport(
                s"cred issuance failed - store segment error: $e",
                credentialRequestCreation
              )
            )
        }
      case Failure(_) =>
        //TODO: need to finalize error message based on different expected exception
        ctx.signal(
          Sig.buildProblemReport(
            "cred issuance failed",
            credentialRequestCreation
          )
        )
    }
  }

  def unexpectedMsg(s: S, m: ProtoMsg): Unit =
    ctx.signal(Sig.buildProblemReport(
      s"Unexpected '${IssueCredMsgFamily.msgType(m.getClass).msgName}' message in current state '${s.status}",
      unexpectedMessage
    ))

  def handleReject(m: Ctl.Reject, s: PostInteractionStarted): Unit = {
    ctx.apply(Rejected(m.comment))
    ctx.send(Msg.buildProblemReport(m.comment.getOrElse("credential rejected"), "rejection"))
  }

  def handleProblemReport(m: ProblemReport, s: S): Unit = {
    val reason = m.resolveDescription

    ctx.apply(ProblemReportReceived(reason))
    ctx.signal(Sig.buildProblemReport(reason, m.tryDescription().code))
  }

  def segmentFailure(e: Any, s: S): Unit = {
    val des = s"failed to retrieve segment: $e"
    ctx.logger.warn(des)
    handleProblemReport(buildProblemReport(des, segmentStorageFailure), s)
  }

  def expiredSegment(msgType: String): Unit = {
    ctx.logger.warn(expiredDataRetentionMsg(msgType))
    ctx.signal(Sig.buildProblemReport(expiredDataRetentionMsg(msgType), expiredDataRetention))
  }


  def handleAck(m: Ack): Unit = {
    ctx.signal(Sig.Ack(m.status))
  }

  def handleStatus(s: S): Unit = {
    ctx.signal(Sig.StatusReport(s.status))
  }

  def handleProposeCredReceived(m: ProposeCred, senderId: String): Unit = {
    val credPreview = m.credential_proposal.map(_.toCredPreviewObject)
    val proposal = CredProposed(m.cred_def_id, credPreview, commentReq(m.comment))

    ctx.storeSegment(segment=proposal) {
      case Success(s: StoredSegment) =>
        ctx.apply(ProposalReceived(senderId, s.segmentKey))
        ctx.signal(Sig.AcceptProposal(m))
      case Failure(e) =>
        ctx.signal(
          Sig.buildProblemReport(
            s"cred proposed failed - store segment error: $e",
            credentialOfferCreation
          )
        )
    }
  }

  def handleOfferCredReceived(m: OfferCred, senderId: String): Unit = {
    val credPreviewObject = m.credential_preview.toCredPreviewObject
    val attachmentObject = m.`offers~attach`.map(toAttachmentObject)
    val offer = CredOffered(Option(credPreviewObject), attachmentObject, commentReq(m.comment), m.price)

    ctx.storeSegment(segment=offer) {
      case Success(s) =>
        ctx.apply(OfferReceived(senderId, s.segmentKey))
        ctx.signal(Sig.AcceptOffer(m))
      case Failure(e) =>
        ctx.signal(
          Sig.buildProblemReport(
            s"cred offer received failed - store segment error: $e",
            credentialOfferCreation
          )
        )
    }
  }

  def handleRequestCredReceived(m: RequestCred, s: S.OfferSent): Unit = {
    ctx.withSegment[CredOffered](s.credOfferRef) {
      case Success(o: Some[CredOffered]) =>
        handleRequestCredReceived(m, buildOfferCred(o), senderId_!(), s.autoIssue)
      case Success(None) => expiredSegment("Credential Offer")
      case Failure(e) => segmentFailure(e, s)
    }

  }
  def handleRequestCredReceived(m: RequestCred, offer: OfferCred, senderId: String, autoIssue: Boolean=false): Unit = {
    val req = CredRequested(m.`requests~attach`.map(toAttachmentObject), commentReq(m.comment))

    ctx.storeSegment(segment=req) {
      case Success(s) =>
        ctx.apply(RequestReceived(senderId, s.segmentKey))
        if (autoIssue) {
          doIssueCredential(offer, buildRequestCred(Option(req)))
        } else {
          ctx.signal(Sig.AcceptRequest(m))
        }
      case Failure(e) =>
        ctx.signal(
          Sig.buildProblemReport(
            s"cred request received failed - store segment error: $e",
            credentialRequestCreation
          )
        )
    }
  }

  def handleIssueCredReceived(m: IssueCred): Unit = {
    val cred = CredIssued(m.`credentials~attach`.map(toAttachmentObject), commentReq(m.comment))
    //TODO: we purposefully are not storing the received credential in the wallet
    // as we are not sure yet if that is the right way to proceed or we want it to
    // store it in persistent store and make it available to the state for further uses

    ctx.storeSegment(segment=cred) {
      case Success(s) =>
        ctx.apply(IssueCredReceived(s.segmentKey))
        ctx.signal(Sig.Received(m))
        if (m.`~please_ack`.isDefined) {
          ctx.send(Ack("OK"))
        }
      case Failure(e) =>
        ctx.signal(
          Sig.buildProblemReport(
            s"issue cred failed - store segment error: $e",
            credentialRequestCreation
          )
        )
    }
  }

  def buildOffer(m: Ctl.Offer)(handler: Try[(CredOffered, OfferCred)] => Unit): Unit = {
    ctx.wallet.createCredOffer(m.cred_def_id) {
      case Success(coc: CredOfferCreated) =>
        val credPreview = buildCredPreview(m.credential_values)
        val credPreviewEventObject = credPreview.toOption.map(_.toCredPreviewObject)
        val attachment = buildAttachment(Some("libindy-cred-offer-0"), payload = coc.offer)
        val attachmentEventObject = toAttachmentObject(attachment)

        val credOffered = CredOffered(
          credPreviewEventObject,
          Seq(attachmentEventObject),
          commentReq(m.comment),
          m.price
        )
        val offerCred = OfferCred(
          credPreview,
          Vector(attachment),
          Option(credOffered.comment),
          m.price
        )
        handler(Try(credOffered -> offerCred))
      case Failure(e) => handler(Failure(e))
    }
  }

  def buildCredPreviewFromObject(cpo: CredPreviewObject): CredPreview = {
    val cpa = cpo.attributes.map { a =>
      CredPreviewAttribute(a.name, a.value, a.mimeType)
    }
    CredPreview(cpo.`type`, cpa.toVector)
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

  def buildOobInvite(offer: OfferCred, s: S.Initialized)
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
          offerAttachment,
          goalCode = Some("issue-vc"),
          goal = Some("To issue a credential"),
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
}
