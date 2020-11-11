package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.conventions.CredValueEncoderV1_0
import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor
import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor.{buildAttachment, buildProtocolMsgAttachment}
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine.{Protocol, ProtocolContextApi}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.InviteUtil
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Msg.prepareInviteUrl
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProof.PresentProofContext
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProblemReportCodes._
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.States.Complete
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.VerificationResults._
import com.evernym.verity.util.{MsgIdProvider, OptionUtil}

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

/*
Aries Community Protocol Spec (for version 0.1):

https://github.com/hyperledger/aries-rfcs/tree/4fae574c03f9f1013db30bf2c0c676b1122f7149/features/0037-present-proof
 */

class PresentProof (implicit val ctx: PresentProofContext)
  extends Protocol[PresentProof, Role, ProtoMsg, Event, State, String](PresentProofDef)
  with ProtocolHelpers[PresentProof, Role, ProtoMsg, Event, State, String] {

  import PresentProof._

  override def applyEvent: ApplyEvent = commonApplyEvent orElse proverApplyEvent orElse verifierApplyEvent

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
    case (_: States.Initialized    , _ , RequestGiven(r)    ) => States.initRequestReceived(r)
    case (s: States.RequestReceived, _,  PresentationUsed(p)) => States.Presented(s.data.addPresentation(p))
    case (s: States.Presented      , _,  PresentationAck(a) ) => States.Presented(s.data.addAck(a))
  }

  def verifierApplyEvent: ApplyEvent = {
    case (_: States.Initialized, _ , RequestUsed(r)       ) => States.initRequestSent(r)
    case (s: States.RequestSent, _ , PresentationGiven(p) ) => States.Complete(s.data.addPresentation(p))
    case (s: States.Complete, _ , AttributesGiven(p)      ) => States.Complete(s.data.addAttributesPresented(p))
    case (s: States.Complete, _ , ResultsOfVerification(r)) => States.Complete(s.data.addVerificationResults(r))
  }



  override def handleProtoMsg: (State, Option[Role], ProtoMsg) ?=> Any = {
    case (_                    , _  , msg: Msg.ProposePresentation) => handleMsgProposal(msg)
    case (States.Initialized(_), _  , msg: Msg.RequestPresentation) => handleMsgRequest(msg)
    case (s: States.RequestSent, _  , msg: Msg.Presentation       ) => handleMsgPresentation(s, msg)
    case (s: State             , r  , msg: Msg.ProblemReport      ) => handleMsgProblemReport(s, r, msg)
    case (States.Presented(_)  , _  , msg: Msg.Ack                ) => apply(PresentationAck(msg.status))
    case (_                    , _  , _  : Msg.Ack                ) => //Acks any other time are ignored
    case (_                    , _  , msg: ProtoMsg               ) => invalidMessageState(msg)
  }


  override def handleControl: Control ?=> Any = statefulHandleControl
  {
    case (States.Uninitialized()   , None             , ctl: Ctl.Init            ) => handleCtlInit(ctl)
    case (s: States.Initialized    , None             , ctl: Ctl.Request         ) => handleCtlRequest(ctl, s.data)
    case (States.Initialized(_)    , None             , ctl: Ctl.AttachedRequest ) => handleCtlAttachedRequest(ctl)
    case (s: States.RequestReceived, Some(Role.Prover), msg: Ctl.AcceptRequest   ) => handleCtlAcceptRequest(s, msg)
    case (s                        , _                , msg: Ctl.Status          ) => handleCtlStatus(s, msg)
    case (s                        , _                , msg: Ctl.Reject          ) => handleCtlReject(s, msg)
    case (_: States.RequestSent    , _                , msg: Ctl.InviteShortened ) =>
      ctx.signal(Sig.Invitation(msg.longInviteUrl, Option(msg.shortInviteUrl), msg.invitationId))
    case (_: States.RequestSent    , Some(role)       , _: Ctl.InviteShorteningFailed ) =>
      ctx.signal(Sig.buildProblemReport("Shortening failed", shorteningFailed))
      apply(Rejection(role.roleNum, "Shortening failed"))
    case (s: State                 , _                , msg: CtlMsg              ) => invalidControlState(s, msg)
  }

  // *****************************
  // HANDLE PROTOCOL MESSAGES
  // *****************************
  def handleMsgRequest(request: Msg.RequestPresentation): Unit = {
    recordSenderId()
    apply(Role.Prover.toEvent)
    extractRequest(request) match {
      case Success(request) =>
        apply(RequestGiven(request))
        val requestedCredentials = ctx.wallet.credentialsForProofReq(request)
        val canFulfill = requestedCredentials match {
          case Success(_) =>
            true
          case Failure(_) => false
        }
        signal(Sig.proofRequestToReviewRequest(request, canFulfill))
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

  def handleMsgPresentation(s: States.RequestSent, msg: Msg.Presentation): Unit = {
    recordSenderId()
    val proofRequest = s.data.requests.last
    val proofRequestJson = DefaultMsgCodec.toJson(_checkRevocationInterval(proofRequest))

    extractPresentation(msg) match {
      case Success(presentations) =>
        val (presentation: ProofPresentation, presentationJson: String) = presentations

        apply(PresentationGiven(presentationJson))
        send(Msg.Ack("OK"))

        val simplifiedProof: AttributesPresented = PresentationResults.presentationToResults(presentation)
        apply(AttributesGiven(DefaultMsgCodec.toJson(simplifiedProof)))

        retrieveLedgerElements(presentation.identifiers, proofRequest.allowsAllSelfAttested) match {
          case Success((schemaJson, credDefJson)) =>
            val verified = ctx.wallet.verifyProof(
                proofRequestJson,
                presentationJson,
                schemaJson,
                credDefJson,
                "{}",
                "{}",
              )

            val correct = checkEncodedAttributes(presentation)

            val validity = verified
              .map(_ && correct)
              .map {
                case true => ProofValidated
                case _ => ProofInvalid
              }
              .getOrElse(ProofUndefined) // verifyProof throw an exception

            apply(ResultsOfVerification(validity))
            signal(Sig.PresentationResult(validity , simplifiedProof))
          case Failure(_) =>
            // Unable to get Ledger Assets
            val validity = ProofUndefined
            apply(ResultsOfVerification(validity))
            signal(Sig.PresentationResult(validity , simplifiedProof))
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


  def handleCtlAttachedRequest(ctr: Ctl.AttachedRequest): Unit = {
    handleMsgRequest(ctr.request)
  }

  def handleCtlRequest(ctr: Ctl.Request, stateData: StateData): Unit = {
    apply(Role.Verifier.toEvent)

    val proofRequest = ProofRequestUtil.requestToProofRequest(ctr)
    val proofRequestStr = proofRequest.map(DefaultMsgCodec.toJson)
    proofRequestStr match {
      case Success(str) =>
        val presentationRequest = Msg.RequestPresentation(
          "",
          Vector(
            buildAttachment(Some(AttIds.request0), str)
          )
        )

        apply(RequestUsed(str))

        if(!ctr.by_invitation.getOrElse(false)) {
          send(presentationRequest)
        }
        else {
          ctx.signal(
            buildOobInvite(presentationRequest, stateData)
              .recover{
                case e: Exception =>
                  ctx.logger.warn(s"Unable to create out-of-band invitation -- ${e.getMessage}")
                  Sig.buildProblemReport(
                    "unable to create out-of-band invitation",
                    invalidRequestedPresentation
                  )
              }
              .get
          )
        }
      case Failure(e) =>
        signal(Sig.buildProblemReport(s"Invalid Request -- ${e.getMessage}", invalidRequestedPresentation))
    }

  }

  def handleCtlAcceptRequest(s: States.RequestReceived, msg: Ctl.AcceptRequest): Unit = {
    val proofRequest = s.data.requests.last
    val proofRequestJson: Try[String] = Try(proofRequest).map(DefaultMsgCodec.toJson)

    val credentialsNeeded: Try[AvailableCredentials] = proofRequestJson
      .flatMap ( ctx.wallet.credentialsForProofReq(_) )
      .map (DefaultMsgCodec.fromJson[AvailableCredentials](_))

    val (credentialsUsedJson, ids) =  credentialsToUse(credentialsNeeded, msg.selfAttestedAttrs)

    doSchemaAndCredDefRetrieval(ids, proofRequest.allowsAllSelfAttested) match {
      case Success((schemaJson, credDefJson)) =>
        ctx.wallet.createProof(
          proofRequestJson.get,
          credentialsUsedJson.get,
          schemaJson,
          credDefJson,
          "{}"
        )
        match {
          case Success(presentation) =>
            val payload = buildAttachment(Some(AttIds.presentation0), presentation)
            send(Msg.Presentation("", Seq(payload)))
            apply(PresentationUsed(presentation))
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

  def handleCtlReject(s: State, msg: Ctl.Reject): Any = {
    val reason = msg.reason.getOrElse("")
    apply(Rejection(ctx.getRoster.selfRole_!.roleNum, reason))
    send(Msg.buildProblemReport(reason, rejection))
  }

  def handleCtlStatus(state: State, msg: Ctl.Status): Unit = {
    val status = state match {
      case s: Complete =>
        val verificationResults = s.data.verificationResults
        val presented = s.data.presentedAttributes
        val results = verificationResults
          .flatMap(x=> presented.map(PresentationResult(x, _)))
        Sig.StatusReport(s.getClass.getSimpleName, results, None)
      case s: HasData =>
        Sig.StatusReport(s.getClass.getSimpleName, None, None)
      case s: State   =>
        Sig.StatusReport(s.getClass.getSimpleName, None, None)
    }
    signal(status)
  }
}

object PresentProof {
  type PresentProofContext = ProtocolContextApi[PresentProof, Role, ProtoMsg, Event, State, String]

  def buildOobInvite(request: Msg.RequestPresentation, stateData: StateData)(implicit ctx: PresentProofContext): Try[Sig.ShortenInvite] = {
    val service = InviteUtil.buildServiced(stateData.agencyVerkey, ctx)

    val attachement = Try(
      buildProtocolMsgAttachment(
        MsgIdProvider.getNewMsgId,
        ctx.threadId_!,
        PresentProofDef.msgFamily,
        request)
    )

    val invite = InviteUtil.buildInvite(
      stateData.agentName,
      stateData.logoUrl,
      stateData.publicDid,
      service,
      attachement
    )

    val signal = for(
      invite          <- invite;
      serviceEndpoint <- Try(ctx.serviceEndpoint);
      inviteUrl       <- Try(prepareInviteUrl(invite, serviceEndpoint));
      inviteId        <- Success(invite.`@id`)
    ) yield Sig.ShortenInvite(inviteId, inviteUrl)

    signal
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

  def retrieveLedgerElements(identifiers: Seq[Identifier], allowsAllSelfAttested: Boolean=false)
                            (implicit ctx: PresentProofContext): Try[(String, String)] = {
    val ids: mutable.Buffer[(String, String)] = mutable.Buffer()

    identifiers.foreach { identifier =>
      ids.append((identifier.schema_id, identifier.cred_def_id))
    }

    doSchemaAndCredDefRetrieval(ids.toSet, allowsAllSelfAttested)
  }

  def doSchemaAndCredDefRetrieval(ids: Set[(String,String)], allowsAllSelfAttested: Boolean)
                                 (implicit ctx: PresentProofContext): Try[(String, String)] = {
    ids.size match {
      case 0 if !allowsAllSelfAttested => Failure(new Exception("No ledger identifiers were included with the Presentation"))
      case _ =>
        doSchemaRetrieval(ids) match {
          case Success(s) =>
            doCredDefRetrieval(ids).map((s,_))
          case Failure(exception) => Failure(exception)
        }
    }

  }

  def doSchemaRetrieval(ids: Set[(String,String)])
                       (implicit ctx: PresentProofContext): Try[String] = {
     Try(
      ids
        .map(_._1)
        .map { x =>
          ctx.ledger.getSchema(x) match {
            case Success(s) => x -> DefaultMsgCodec.toJson(s.schema)
            case Failure(e) => throw new Exception(s"Unable to retrieve schema from ledger", e)
          }
        }
        .map(t => s""" "${t._1}": ${t._2} """)
        .mkString("{", ",", "}")
    )
  }


  def doCredDefRetrieval(ids: Set[(String,String)])
                        (implicit ctx: PresentProofContext): Try[String] = {
    Try(
      ids
        .map(_._2)
        .map { x =>
          ctx.ledger.getCredDef(x) match {
            case Success(s) => x -> DefaultMsgCodec.toJson(s.credDef)
            case Failure(e) => throw new Exception(s"Unable to retrieve cred def from ledger", e)
          }
        }
        .map(t => s""" "${t._1}": ${t._2} """)
        .mkString("{", ",", "}")
    )
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
    ctx.send(Msg.buildProblemReport(errorMsg, unexpectedMessage))
  }

  def rejectableState(state: State): Boolean = {
    state match {
      case s: HasData => s match {
        // Allowed rejection states
        case _ @ (_: States.ProposalReceived |
                  _: States.RequestSent      |
                  _: States.RequestReceived  |
                  _: States.ProposalSent     |
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
