package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.did.didcomm.v1.conventions.CredValueEncoderV1_0
import com.evernym.verity.did.didcomm.v1.decorators.AttachmentDescriptor
import com.evernym.verity.did.didcomm.v1.decorators.AttachmentDescriptor.{buildAttachment, buildProtocolMsgAttachment}
import com.evernym.verity.observability.metrics.InternalSpan
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.ShortenInvite
import com.evernym.verity.protocol.engine.asyncapi.wallet.CredForProofResult
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine.{ProtoRef, Protocol}
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.outofband.v_1_0.InviteUtil
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.prepareInviteUrl
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.ProposePresentation
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProof.PresentProofContext
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProblemReportCodes._
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Role.{Prover, Verifier}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.States.Complete
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.VerificationResults._
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.legacy.PresentProofLegacy
import com.evernym.verity.urlshortener.{UrlShortened, UrlShorteningFailed}
import com.evernym.verity.util.{MsgIdProvider, OptionUtil}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/*
Aries Community Protocol Spec (for version 0.1):

https://github.com/hyperledger/aries-rfcs/tree/4fae574c03f9f1013db30bf2c0c676b1122f7149/features/0037-present-proof
 */

class PresentProof(implicit val ctx: PresentProofContext)
  extends Protocol[PresentProof, Role, ProtoMsg, Event, State, String](PresentProofDef)
    with ProtocolHelpers[PresentProof, Role, ProtoMsg, Event, State, String]
    with PresentProofLegacy {

  import PresentProof._

  override def applyEvent: ApplyEvent =
    commonApplyEvent orElse
      proverApplyEvent orElse
      verifierApplyEvent orElse
      legacyApplyEvent

  override def handleProtoMsg: (State, Option[Role], ProtoMsg) ?=> Any =
    mainProtoMsgHandler orElse
      legacyProtoMsg orElse
      invalidProtoMsgHandler

  override def handleControl: Control ?=> Any =
    mainHandleControl orElse
      legacyControl orElse
      invalidHandleControl

  def mainHandleControl: Control ?=> Any = statefulHandleControl {
    case (States.Uninitialized()    , None,           ctl: Ctl.Init               ) => handleCtlInit(ctl)
    case (s: States.Initialized     , None,           ctl: Ctl.Request            ) => handleCtlRequest(ctl, s.data)
    case (States.Initialized(_)     , None,           ctl: Ctl.Propose            ) => handleCtlPropose(ctl)
    case (_: States.RequestReceived , Some(Prover),   ctl: Ctl.Propose            ) => handleCtlPropose(ctl)
    case (States.Initialized(_)     , None,           ctl: Ctl.AttachedRequest    ) => handleCtlAttachedRequest(ctl)
    case (s: States.RequestReceived , Some(Prover),   msg: Ctl.AcceptRequest      ) =>
      withSegment("accepting request", s.data.requests.head, handleCtlAcceptRequest(msg))
    case (s: States.ProposalReceived, Some(Verifier), msg: Ctl.AcceptProposal     ) =>
      withSegment("accepting proposal", s.data.proposals.head, handleCtlAcceptProposal(msg))
    case (s: States.ProposalReceived, Some(Verifier), msg: Ctl.Request            ) => handleCtlRequest(msg, s.data)
    case (s                         , _,              msg: Ctl.Status             ) => handleCtlStatus(s, msg)
    case (s                         , _,              msg: Ctl.Reject             ) => handleCtlReject(s, msg)
    case (s: State                  , _             , msg: CtlMsg                 ) => invalidControlState(s, msg)
  }

  def invalidHandleControl: Control ?=> Any = statefulHandleControl {
    case (s: State                  , _             , msg: CtlMsg                 ) => invalidControlState(s, msg)
  }

  // Proto Messages
  def mainProtoMsgHandler: (State, Option[Role], ProtoMsg) ?=> Any = {
    case (States.Initialized(_),  _, msg: Msg.RequestPresentation) => handleMsgRequest(msg)

    case (States.Initialized(_),  _, msg: Msg.ProposePresentation) =>
      apply(Role.Verifier.toEvent)
      handleMsgProposePresentation(msg)

    case (s: States.RequestSent,  _, msg: Msg.Presentation       ) =>
      withSegment("processing presentation", s.data.requests.head, handleMsgPresentation(msg), sendMsg = true)

    case (_: States.ProposalSent, _, msg: Msg.RequestPresentation) => handleMsgRequest(msg)
    case (_: States.RequestSent,  _, msg: Msg.ProposePresentation) => handleMsgProposePresentation(msg)
    case (s: State,               r, msg: Msg.ProblemReport      ) => handleMsgProblemReport(s, r, msg)
    case (States.Presented(_),    _, msg: Msg.Ack                ) => apply(PresentationAck(msg.status))
    case (_,                      _, _  : Msg.Ack                ) => //Acks any other time are ignored
    case (_,                      _, msg: Msg.ProposePresentation) => handleMsgProposal(msg)
    case (_,                      _, msg: ProtoMsg               ) => invalidMessageState(msg)
  }

  def invalidProtoMsgHandler: (State, Option[Role], ProtoMsg) ?=> Any = {
    case (_,                            _, msg: ProtoMsg               ) => invalidMessageState(msg)
  }

  def commonApplyEvent: ApplyEvent = {
    case (_: States.Uninitialized , _ , Participants(selfId, otherId)  ) =>
      (States.Initialized(StateData()), setupParticipantIds(selfId, otherId))

    case (States.Initialized(data)   , _ , context: AgentContext       ) =>
      States.Initialized(data.copy(
        agentName = context.agentName,
        logoUrl = context.logoUrl,
        agencyVerkey = context.agencyVerKey,
        publicDid = context.publicDid
      ))

    case (s: State   , _ , MyRole(n)                      ) =>
      (s, setRole(Role.numToRole(n), Role.otherRole(n)))

    case (s: HasData, _ , Rejection(role, reason)) if rejectableState(s) =>
      States.Rejected(s.data, Role.numToRole(role), OptionUtil.blankOption(reason))

    case (s:State, _ , Participants(selfId, otherId))                    =>
      (s, setupParticipantIds(selfId, otherId))
  }

  def proverApplyEvent: ApplyEvent = {
    case (_: States.Initialized    , _, RequestGivenRef(r)           )  => States.initRequestReceived(r)
    case (_: States.Initialized    , _, PresentationProposedRef(r))     => States.initProposalSent(r)
    case (s: States.ProposalSent   , _, RequestGivenRef(r)           )  => States.RequestReceived(s.data.addRequest(r))
    case (s: States.RequestReceived, _, PresentationUsedRef(p)       )  => States.Presented(s.data.addPresentation(p))
    case (s: States.RequestReceived, _, PresentationProposedRef(r))     => States.ProposalSent(s.data.addProposal(r))
    case (s: States.Presented      , _, PresentationAck(a)        )     => States.Presented(s.data.addAck(a))
  }

  def verifierApplyEvent: ApplyEvent = {
    case (_: States.Initialized     , _, RequestUsedRef(r)          ) => States.initRequestSent(r)
    case (_: States.Initialized     , _, ProposeReceivedRef(r)   )    => States.initProposalReceived(r)
    case (s: States.ProposalReceived, _, RequestUsedRef(r)          ) => States.RequestSent(s.data.addRequest(r))
    case (s: States.RequestSent     , _, ProposeReceivedRef(r)   )    => States.ProposalReceived(s.data.addProposal(r))
    case (s: States.RequestSent     , _, PresentationGivenRef(p)    ) => States.Complete(s.data.addPresentation(p))
    case (s: States.Complete        , _, ResultsOfVerification(r))    => States.Complete(s.data.addVerificationResults(r))
  }

  def withSegment[T](context: String, id: SegmentKey, f: T => Unit, sendMsg: Boolean = false): Unit = {
    ctx.withSegment[T](id) {
      case Success(Some(s)) => f(s)
      case Success(None)  => reportSegNotFound(context, sendMsg = sendMsg)
      case Failure(e)     => reportSegRetrieveFailed(context, sendMsg)
    }
  }
  // *****************************
  // HANDLE PROTOCOL MESSAGES
  // *****************************
  def handleMsgRequest(request: Msg.RequestPresentation): Unit = {
    recordSenderId()
    apply(Role.Prover.toEvent)
    extractRequest(request) match {
      case Success(request) =>
        ctx.storeSegment(segment=RequestUsed(request)) {
          case Success(s) =>
            ctx.apply(RequestGivenRef(s.segmentKey))
            ctx.wallet.credentialsForProofReq(request) {
              case Success(_) =>
                signal(Sig.proofRequestToReviewRequest(request, canFulfill = true))
              case Failure(_) =>
                signal(Sig.proofRequestToReviewRequest(request, canFulfill = false))
            }
          case Failure(e) => reportSegStoreFailed("error during processing request presentation")
        }
      case Failure(e) => send(
        Msg.buildProblemReport(s"Invalid request -- ${e.getMessage}", invalidRequest)
      )
    }
  }

  def _checkRevocationInterval(request: ProofRequest): ProofRequest = {
    val interval = request.non_revoked match {
      case Some(i) =>
        if(i.from.isEmpty && i.to.isEmpty) None
        else Some(i)
      case None => None
    }
    request.copy(non_revoked=interval)
  }

  def handleMsgProposePresentation(msg: Msg.ProposePresentation): Unit = {
    ctx.storeSegment(segment=ProposeReceived(
      msg.presentation_proposal.attributes.map(_.toEvent),
      msg.presentation_proposal.predicates.map(_.toEvent),
    )) {
      case Success(s) =>
        ctx.apply(ProposeReceivedRef(s.segmentKey))
        ctx.signal(Sig.ReviewProposal(msg.presentation_proposal.attributes, msg.presentation_proposal.predicates, msg.comment))
      case Failure(e) => reportSegStoreFailed("error during processing propose presentation")
    }
  }

  def handleMsgPresentation(msg: Msg.Presentation)(requestUsed: RequestUsed): Unit = {
    recordSenderId()
    val proofRequest = DefaultMsgCodec.fromJson[ProofRequest](requestUsed.requestRaw)
    val proofRequestJson = DefaultMsgCodec.toJson(_checkRevocationInterval(proofRequest))

    extractPresentation(msg) match {
      case Success((presentation: ProofPresentation, presentationJson: String)) =>
        ctx.storeSegment(segment=PresentationGiven(presentationJson)) {
          case Success(s) =>
            ctx.apply(PresentationGivenRef(s.segmentKey))
            send(Msg.Ack("OK"))

            val simplifiedProof: AttributesPresented = PresentationResults.presentationToResults(presentation)
            retrieveLedgerElements(presentation.identifiers, proofRequest.allowsAllSelfAttested) {
              case Success((schemaJson, credDefJson)) =>
                ctx.metricsWriter.runWithSpan("processPresentation","PresentProof", InternalSpan) {
                  ctx.wallet.verifyProof(
                    proofRequestJson,
                    presentationJson,
                    schemaJson,
                    credDefJson,
                    "{}",
                    "{}",
                  ) { proofVerifResult =>
                    val correct = checkEncodedAttributes(presentation)
                    val validity = proofVerifResult
                      .map(_.result && correct)
                      .map {
                        case true => ProofValidated
                        case _ => ProofInvalid
                      }
                      .getOrElse(ProofUndefined) // verifyProof throw an exception

                    apply(ResultsOfVerification(validity))
                    signal(Sig.PresentationResult(validity, simplifiedProof))
                  }
                }
              case Failure(_) =>
                // Unable to get Ledger Assets
                val validity = ProofUndefined
                apply(ResultsOfVerification(validity))
                signal(Sig.PresentationResult(validity , simplifiedProof))
            }
          case Failure(e) => reportSegStoreFailed("error during processing presentation")
        }
      case Failure(e) => send(
        Msg.buildProblemReport(s"Invalid presentation -- ${e.getMessage}", invalidPresentation)
      )
    }
  }

  def handleMsgProposal(msg: Msg.ProposePresentation): Unit = {
    recordSenderId()

    send(Msg.buildProblemReport("propose-presentation is not supported", unimplemented))
  }

  def handleMsgProblemReport(state: State, role: Option[Role], msg: Msg.ProblemReport): Unit = {
    val isRejection = true // Currently I don't see how we can tell between an problem and an rejection
    if(isRejection) {
      if (rejectableState(state)) {
        val reason = msg.resolveDescription

        val roleNum = role.map(_.roleNum).getOrElse(0) //not sure what we should if the role is not defined here
        apply(Rejection(roleNum, reason))
        signal(Sig.buildProblemReport(s"Rejected -- $reason", rejection))
      }
      else {
        send(
          Msg.buildProblemReport(
            "Protocol not is a state where rejection is allowed",
            rejectionNotAllowed
          )
        )
      }
    }
  }

  // *****************************
  // HANDLE CONTROL MESSAGES
  // *****************************
  def handleCtlInit(ctl: Ctl.Init): Unit = {
    apply(Participants(ctl.selfId, ctl.otherId.getOrElse("")))
    apply(AgentContext(ctl.agentName, ctl.logoUrl, ctl.agencyVerkey, ctl.publicDid))
  }

  def handleCtlPropose(ctp: Ctl.Propose): Unit = {
    if (ctx.getRoster.selfRole.isEmpty) {
      apply(Role.Prover.toEvent)
    }

    val attributes = ctp.attributes.getOrElse(List())
    val predicates = ctp.predicates.getOrElse(List())

    ctx.storeSegment(segment=PresentationProposed(attributes.map(_.toEvent), predicates.map(_.toEvent))) {
      case Success(s) =>
        ctx.apply(PresentationProposedRef(s.segmentKey))
        ctx.send(ProposePresentation(
          comment = ctp.comment,
          presentation_proposal = PresentationPreview(attributes, predicates)
        ))
      case Failure(e) => reportSegStoreFailed("error during processing propose")
    }
  }

  def handleCtlAttachedRequest(ctr: Ctl.AttachedRequest): Unit = {
    handleMsgRequest(ctr.request)
  }

  def handleCtlRequest(ctr: Ctl.Request, stateData: StateData): Unit = {
    apply(Role.Verifier.toEvent)

    val proofRequest = ProofRequestUtil.requestToProofRequest(ctr)
    val proofRequestStr = proofRequest.map(DefaultMsgCodec.toJson)

    proofRequestStr match {
      case Success(str) =>
        ctx.storeSegment(segment=RequestUsed(str)) {
          case Success(s) =>
            ctx.apply(RequestUsedRef(s.segmentKey))

            val presentationRequest = Msg.RequestPresentation("", Vector(buildAttachment(Some(AttIds.request0), str)))
            if(!ctr.by_invitation.getOrElse(false)) { ctx.send(presentationRequest) }
            else { sendInvite(presentationRequest, stateData) }
          case Failure(e) => reportSegStoreFailed("error during processing request")
        }
      case Failure(e) =>
        signal(Sig.buildProblemReport(s"Invalid Request -- ${e.getMessage}", invalidRequestedPresentation))
    }
  }

  def sendInvite(presentationRequest: Msg.RequestPresentation, stateData: StateData): Unit = {
    buildOobInvite(definition.protoRef, presentationRequest, stateData) {
      case Success(invite) =>
        ctx.urlShortening.shorten(invite.inviteURL) {
          case Success(us: UrlShortened) =>
            ctx.signal(Sig.Invitation(invite.inviteURL, Option(us.shortUrl), invite.invitationId))
          case Success(usf: UrlShorteningFailed) =>
            ctx.signal(Sig.buildProblemReport(usf.errorMsg, usf.errorCode))
            apply(Rejection(ctx.getRoster.selfRole.map(_.roleNum).getOrElse(0), "Shortening failed"))
          case _ =>
            ctx.signal(Sig.buildProblemReport("Shortening failed", shorteningFailed))
            apply(Rejection(ctx.getRoster.selfRole.map(_.roleNum).getOrElse(0), "Shortening failed"))
        }
      case Failure(e) =>
        ctx.logger.warn(s"Unable to create out-of-band invitation -- ${e.getMessage}")
        Sig.buildProblemReport(
          "unable to create out-of-band invitation",
          invalidRequestedPresentation
        )
    }
  }

  def handleCtlAcceptRequest(msg: Ctl.AcceptRequest)(requestUsed: RequestUsed): Unit = {
    val proofRequest = DefaultMsgCodec.fromJson[ProofRequest](requestUsed.requestRaw)
    val proofRequestJson = DefaultMsgCodec.toJson(proofRequest)

    ctx.wallet.credentialsForProofReq(proofRequestJson) { credentialsNeededJson: Try[CredForProofResult] =>
      val credentialsNeeded =
        credentialsNeededJson.map(_.cred).map(DefaultMsgCodec.fromJson[AvailableCredentials](_))
      val (credentialsUsedJson, ids) = credentialsToUse(credentialsNeeded, msg.selfAttestedAttrs)

      doSchemaAndCredDefRetrieval(ids, proofRequest.allowsAllSelfAttested) {
        case Success((schemaJson, credDefJson)) =>
          ctx.wallet.createProof(
            proofRequestJson,
            credentialsUsedJson.get, // This may throw error?
            schemaJson,
            credDefJson,
            "{}"
          ) {
            case Success(proofCreated) =>
              val payload = buildAttachment(Some(AttIds.presentation0), proofCreated.proof)
              ctx.storeSegment(segment=PresentationUsed(proofCreated.proof)) {
                case Success(s) =>
                  send(Msg.Presentation("", Seq(payload)))
                  ctx.apply(PresentationUsedRef(s.segmentKey))
                case Failure(e) => reportSegStoreFailed("error during processing accept request")
              }
            case Failure(e) => signal(
              Sig.buildProblemReport(
                s"Unable to crate proof presentation -- ${e.getMessage}",
                "presentation-creation-failure"
              )
            )
          }
        case Failure(e) => signal(
          Sig.buildProblemReport(s"Ledger assets unavailable -- ${e.getMessage}", ledgerAssetsUnavailable)
        )

      }
    }
  }

  def handleCtlAcceptProposal(msg: Ctl.AcceptProposal)(proposal: ProposeReceived): Unit = {
      val proofRequest = ProofRequestUtil.proposalToProofRequest(
        PresentationPreview.fromEvt(proposal),
        msg.name.getOrElse(""),
        msg.non_revoked
      )
      val proofRequestStr = proofRequest.map(DefaultMsgCodec.toJson)

      proofRequestStr match {
        case Success(str) =>
          val presentationRequest = Msg.RequestPresentation(
            "",
            Vector(
              buildAttachment(Some(AttIds.request0), str)
            )
          )

          ctx.storeSegment(segment=RequestUsed(str)) {
            case Success(s) =>
              ctx.apply(RequestUsedRef(s.segmentKey))
              send(presentationRequest)
            case Failure(e) => reportSegStoreFailed("error during processing accept proposal")
          }
        case Failure(e) =>
          signal(Sig.buildProblemReport(s"Invalid Request -- ${e.getMessage}", invalidRequestedPresentation))
      }
  }

  def handleCtlReject(s: State, msg: Ctl.Reject): Any = {
    val reason = msg.reason.getOrElse("")
    apply(Rejection(ctx.getRoster.selfRole_!.roleNum, reason))
    send(Msg.buildProblemReport(reason, rejection))
  }

  def handleCtlStatus(state: State, msg: Ctl.Status): Unit = {
    state match {
      case s: Complete =>
        s.data.verificationResults
          .flatMap(x=> s.data.presentation.map( ref => {
            ctx.withSegment[PresentationGiven](ref) {
              case Success(Some(p)) =>
                val presentation = DefaultMsgCodec.fromJson[ProofPresentation](p.presentation)
                val simplifiedProof: AttributesPresented = PresentationResults.presentationToResults(presentation)
                val res = Some(PresentationResult(x, simplifiedProof))
                signal(Sig.StatusReport(s.getClass.getSimpleName, res, None))
              case Success(None) => signal(Sig.StatusReport(s.getClass.getSimpleName, None, None))
              case Failure(e) => reportSegStoreFailed("error during processing status")
            }
          }))
      case s: HasData =>
        signal(Sig.StatusReport(s.getClass.getSimpleName, None, None))
      case s: State   =>
        signal(Sig.StatusReport(s.getClass.getSimpleName, None, None))
    }
  }

  def retrieveLedgerElements(identifiers: Seq[Identifier], allowsAllSelfAttested: Boolean=false)
                            (handler: Try[(String, String)] => Unit): Unit = {
    val ids: mutable.Buffer[(String, String)] = mutable.Buffer()
    identifiers.foreach { identifier =>
      val fqSchemaId = ctx.ledger.fqSchemaId(identifier.schema_id)
      val fqCredDefId = {
        //TODO: To be finalized (causing issues here and there)
        val credDefId = identifier.cred_def_id.replace(identifier.schema_id, fqSchemaId)
        ctx.ledger.fqCredDefId(credDefId)
      }
      ids.append((fqSchemaId, fqCredDefId))
    }
    doSchemaAndCredDefRetrieval(ids.toSet, allowsAllSelfAttested)(handler)
  }

  def doSchemaAndCredDefRetrieval(ids: Set[(String,String)], allowsAllSelfAttested: Boolean)
                                       (handler: Try[(String, String)] => Unit): Unit = {
    ids.size match {
      case 0 if !allowsAllSelfAttested => Failure(new Exception("No ledger identifiers were included with the Presentation"))
      case _ =>
        doSchemaRetrieval(ids.map(_._1)) {
          case Success(schema)    => doCredDefRetrieval(schema, ids.map(_._2))(handler)
          case Failure(exception) => handler(Failure(exception))
        }
    }

    def doSchemaRetrieval(ids: Set[String])(handler: Try[String] => Unit): Unit = {
      ctx.ledger.resolveSchemas(ids) {
        case Success(schemas) if schemas.size == ids.size =>
          val retrievedSchemasJson = schemas.map { schema =>
            s""""${schema.fqId}": ${schema.json}"""
          }.mkString("{", ",", "}")
          handler(Success(retrievedSchemasJson))
        case Success(_) => handler(Failure(new Exception("Unable to retrieve schema from ledger")))
        case Failure(e) => handler(Failure(new Exception("Unable to retrieve schema from ledger", e)))
      }
    }


    def doCredDefRetrieval(schemas: String, credDefIds: Set[String])
                          (handler: Try[(String, String)] => Unit): Unit = {
      ctx.ledger.resolveCredDefs(credDefIds) {
        case Success(credDefs) if credDefs.size == ids.size =>
          val retrievedCredDefJson = credDefs.map { credDef =>
            s""""${credDef.fqId}": ${credDef.json}"""
          }.mkString("{", ",", "}")
          handler(Success((schemas, retrievedCredDefJson)))
        case Success(_) => throw new Exception("Unable to retrieve cred def from ledger")
        case Failure(e) => throw new Exception("Unable to retrieve cred def from ledger", e)
      }
    }
  }
}

object PresentProof {
  type PresentProofContext = ProtocolContextApi[PresentProof, Role, ProtoMsg, Event, State, String]

  def reportSegNotFound(op: String, sendMsg: Boolean = false)
                       (implicit ctx: PresentProofContext): Unit = {
    ctx.signal(Sig.buildProblemReport(s"error while $op", segmentedRetrieveFailed))
    if (sendMsg)
      ctx.send(Msg.buildProblemReport(s"error while $op", segmentedRetrieveFailed))
  }

  def reportSegRetrieveFailed(op: String, sendMsg: Boolean = false)
                             (implicit ctx: PresentProofContext): Unit = {
      ctx.signal(Sig.buildProblemReport(s"error while $op", segmentedRetrieveFailed))
      if (sendMsg)
        ctx.send(Msg.buildProblemReport(s"error while $op", segmentedRetrieveFailed))
  }

  def reportSegStoreFailed(errorMsg: String)(implicit ctx: PresentProofContext): Unit = {
    ctx.signal(Sig.buildProblemReport(errorMsg, segmentedStoreFailed))
  }

  def buildOobInvite(protoRef: ProtoRef, request: Msg.RequestPresentation, stateData: StateData)
                    (handler: Try[ShortenInvite] => Unit)
                    (implicit ctx: PresentProofContext): Unit = {
    InviteUtil.withServiced(stateData.agencyVerkey, ctx) {
      case Success(service) =>
        val attachement: AttachmentDescriptor = buildProtocolMsgAttachment(
          MsgIdProvider.getNewMsgId,
          ctx.threadId_!,
          PresentProofDef.msgFamily,
          request
        )
        val invite = InviteUtil.buildInviteWithThreadedId(
          protoRef,
          ctx.getRoster.selfId_!,
          ctx.`threadId_!`,
          stateData.agentName,
          stateData.logoUrl,
          stateData.publicDid,
          service,
          attachement,
          goalCode = Some("request-proof"),
          goal = Some("To request a proof"),
          ctx.serviceKeyDidFormat
        )

        handler(Success(
          ShortenInvite(invite.`@id`, prepareInviteUrl(invite, ctx.serviceEndpoint))
        ))
      case Failure(ex) =>
        handler(Failure(ex))
    }
  }

  def extractPresentation(msg: Msg.Presentation):Try[(ProofPresentation, String)] = {
    extractAttachment(AttIds.presentation0, msg.`presentations~attach`) match {
      case Success(json) => Try{
        (DefaultMsgCodec.fromJson[ProofPresentation](json), json)
      }
      case Failure(e) => Failure(e)
    }
  }

  def extractRequest(msg: Msg.RequestPresentation): Try[String] = {
    extractAttachment(AttIds.request0, msg.`request_presentations~attach`)
  }

  def extractAttachment(attachmentId: String, attachments: Seq[AttachmentDescriptor]): Try[String] ={
    Try(attachments.size)
      .getOrElse(throw new Exception("Attachment decorator don't have an Attachment"))
    match {
      case 1 =>
        attachments.head match {
          case att if att.`@id`.contains(attachmentId) => Try(AttachmentDescriptor.extractString(att))
          case _ => Failure(new Exception("Attachment Id don't match"))
        }
      case _ => Failure(new Exception("Attachment has unsupported multiple attachments"))
    }
  }

  def checkEncodedAttributes(presentation: ProofPresentation): Boolean = {
    presentation
      .requested_proof
      .revealed_attrs
      .values
      .map{ x=>
        x.encoded == CredValueEncoderV1_0.encodedValue(x.raw)
      }
      .forall(identity)
  }

  def credentialsToUse(credentialsNeeded: Try[AvailableCredentials],
                       selfAttestedAttributes: Map[String, String]=Map.empty): (Try[String], Set[(String, String)]) = {
    val ids: mutable.Buffer[(String,String)] = mutable.Buffer()

    val credentialsUsedJson = credentialsNeeded
      .map{ creds =>
        val requestedAttributes: Map[String, AttributeUsed] = creds.attrs
          .foldLeft(Map[String, AttributeUsed]()) { (col, entity) =>
            val referent = entity._1
            val info = entity._2.headOption.map(_.cred_info)
            info match {
              case Some(i) =>
                ids.append((i.schema_id, i.cred_def_id))
                col + (
                  referent -> AttributeUsed(
                    i.referent,
                    revealed = true,
                    None
                  )
                  )
              case None => col
            }

          }
        val requestedPredicates: Map[String, PredicateUsed] = creds.predicates
          .foldLeft(Map[String, PredicateUsed]()) { (col, entity) =>
            val referent = entity._1
            val info = entity._2.head.cred_info
            ids.append((info.schema_id, info.cred_def_id))
            col + (
              referent -> PredicateUsed(
                info.referent,
                None
              )
              )
          }
        CredentialsUsed(selfAttestedAttributes, requestedAttributes, requestedPredicates)
      }
      .map(DefaultMsgCodec.toJson)

    (credentialsUsedJson, ids.toSet)
  }

  def invalidMessageState(invalidMsg: ProtoMsg)
                         (implicit ctx: PresentProofContext): Unit = {
    val msgName: String = invalidMsg.getClass.getSimpleName
    val errorMsg = s"Invalid '$msgName' message in current state"
    ctx.send(
      Msg.buildProblemReport(errorMsg, invalidMessageStateError)
    )
  }

  def invalidControlState(curState: State, invalidMsg: CtlMsg)
                         (implicit ctx: PresentProofContext): Unit = {
    val msgName: String = PresentProofMsgFamily.msgType(invalidMsg.getClass).msgName
    val stateName: String = curState.getClass.getSimpleName
    val errorMsg = s"Unexpected '$msgName' message in current state '$stateName"
    ctx.signal(
      Sig.buildProblemReport(errorMsg, unexpectedMessage)
    )
  }

  def rejectableState(state: State): Boolean = {
    state match {
      case s: HasData => s match {
        // Allowed rejection states
        case _ @ (_: States.ProposalReceived |
                  _: States.RequestSent |
                  _: States.RequestReceived |
                  _: States.ProposalSent |
                  _: States.Presented) => true
        // Disallowed states
        case _ => false
      }
      case _ => false
    }
  }

  def recordSenderId()(implicit ctx: PresentProofContext): Unit = {
    ctx
      .getInFlight
      .sender
      .id
      .foreach { id =>
        val r = ctx.getRoster
        if(r.participantIndex(id).isEmpty) {
          ctx.apply(Participants("", id))
          r.selfRole.foreach{ selfRole =>
            ctx.apply(MyRole(selfRole.roleNum))
          }
        }
      }
  }
}
