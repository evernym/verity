package com.evernym.verity.protocol.protocols.presentproof.v_1_0.legacy

import com.evernym.verity.actor.wallet.CredForProofReqCreated
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.metrics.{InternalSpan, MetricsWriter}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor.buildAttachment
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine.Protocol
import com.evernym.verity.protocol.protocols.ProtocolHelpers
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProblemReportCodes._
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Role.{Prover, Verifier}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.VerificationResults._
import com.evernym.verity.protocol.protocols.presentproof.v_1_0._

import scala.collection.mutable
import scala.util.{Failure, Success, Try}

trait PresentProofLegacy
  extends Protocol[PresentProof, Role, ProtoMsg, Event, State, String]
    with ProtocolHelpers[PresentProof, Role, ProtoMsg, Event, State, String] {

  import PresentProof._

  override implicit val ctx: PresentProof.PresentProofContext

  def metricsWriter: MetricsWriter

  // TODO: Remove All Legacy control, protocol, and events during Ticket=VE-2605
  def legacyApplyEvent: ApplyEvent = {
    //Prover Events
    case (_: StatesLegacy.Initialized    , _, RequestGiven(r)           ) => StatesLegacy.initRequestReceived(r)
    case (_: StatesLegacy.Initialized    , _, PresentationProposed(a, p)) => StatesLegacy.initProposalSent(a, p)
    case (s: StatesLegacy.ProposalSent   , _, RequestGiven(r)           ) => StatesLegacy.RequestReceived(s.data.addRequest(r))
    case (s: StatesLegacy.RequestReceived, _, PresentationUsed(p)       ) => StatesLegacy.Presented(s.data.addPresentation(p))
    case (s: StatesLegacy.RequestReceived, _, PresentationProposed(a, p)) => StatesLegacy.ProposalSent(s.data.addProposal(a, p))

    //Verifier Events
    case (_: StatesLegacy.Initialized     , _, RequestUsed(r)          ) => StatesLegacy.initRequestSent(r)
    case (_: StatesLegacy.Initialized     , _, ProposeReceived(a, p)   ) => StatesLegacy.initProposalReceived(a, p)
    case (s: StatesLegacy.ProposalReceived, _, RequestUsed(r)          ) => StatesLegacy.RequestSent(s.data.addRequest(r))
    case (s: StatesLegacy.RequestSent     , _, PresentationGiven(p)    ) => StatesLegacy.Complete(s.data.addPresentation(p))
    case (s: StatesLegacy.RequestSent     , _, ProposeReceived(a, p)   ) => StatesLegacy.ProposalReceived(s.data.addProposal(a, p))
  }

  def legacyProtoMsg: (State, Option[Role], ProtoMsg) ?=> Any = {
    case (StatesLegacy.Initialized(_),  _, msg: Msg.RequestPresentation) => handleMsgRequestLegacy(msg)
    case (StatesLegacy.Initialized(_),  _, msg: Msg.ProposePresentation) => apply(Role.Verifier.toEvent); handleMsgProposePresentationLegacy(msg)
    case (s: StatesLegacy.RequestSent,  _, msg: Msg.Presentation       ) => handleMsgPresentationLegacy(s, msg)
  }

  def legacyControl: Control ?=> Any = statefulHandleControl {
    case (s: StatesLegacy.RequestReceived , Some(Prover),   msg: Ctl.AcceptRequest      ) => handleCtlAcceptRequestLegacy(s, msg)
    case (s: StatesLegacy.ProposalReceived, Some(Verifier), msg: Ctl.AcceptProposal     ) => handleCtlAcceptProposalLegacy(s, msg)
  }

  def handleMsgRequestLegacy(request: Msg.RequestPresentation): Unit = {
    apply(Role.Prover.toEvent)
    extractRequest(request) match {
      case Success(request) =>
        apply(RequestGiven(request))
        ctx.wallet.credentialsForProofReq(request) {
          case Success(_) =>
            signal(Sig.proofRequestToReviewRequest(request, canFulfill = true))
          case Failure(_) =>
            signal(Sig.proofRequestToReviewRequest(request, canFulfill = false))
        }
      case Failure(e) => send(
        Msg.buildProblemReport(s"Invalid request -- ${e.getMessage}", invalidRequest)
      )
    }
  }

  def handleCtlAcceptRequestLegacy(s: StatesLegacy.RequestReceived, msg: Ctl.AcceptRequest): Unit = {
    val proofRequest: ProofRequest = s.data.requests.head
    val proofRequestJson: String = DefaultMsgCodec.toJson(proofRequest)

    ctx.wallet.credentialsForProofReq(proofRequestJson) { credentialsNeededJson: Try[CredForProofReqCreated] =>
      val credentialsNeeded =
        credentialsNeededJson.map(_.cred).map(DefaultMsgCodec.fromJson[AvailableCredentials](_))
      val (credentialsUsedJson, ids) = credentialsToUse(credentialsNeeded, msg.selfAttestedAttrs)

      doSchemaAndCredDefRetrievalLegacy(ids, proofRequest.allowsAllSelfAttested) {
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
              send(Msg.Presentation("", Seq(payload)))
              apply(PresentationUsed(proofCreated.proof))
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

  def handleCtlAcceptProposalLegacy(s: StatesLegacy.ProposalReceived, msg: Ctl.AcceptProposal): Unit = {
    val proposal = s.data.proposals.head

    val proofRequest = ProofRequestUtil.proposalToProofRequest(proposal, msg.name.getOrElse(""), msg.non_revoked)
    val proofRequestStr = proofRequest.map(DefaultMsgCodec.toJson)
    proofRequestStr match {
      case Success(str) =>
        val presentationRequest = Msg.RequestPresentation(
          "",
          Vector(
            buildAttachment(Some(AttIds.request0), str)
          )
        )

        send(presentationRequest)
        apply(RequestUsed(str))
      case Failure(e) =>
        signal(Sig.buildProblemReport(s"Invalid Request -- ${e.getMessage}", invalidRequestedPresentation))
    }
  }

  def handleMsgPresentationLegacy(s: StatesLegacy.RequestSent, msg: Msg.Presentation): Unit = {
    val proofRequest = s.data.requests.head
    val proofRequestJson = DefaultMsgCodec.toJson(_checkRevocationIntervalLegacy(proofRequest))

    extractPresentation(msg) match {
      case Success(presentations) =>
        val (presentation: ProofPresentation, presentationJson: String) = presentations

        apply(PresentationGiven(presentationJson))
        send(Msg.Ack("OK"))

        val simplifiedProof: AttributesPresented = PresentationResults.presentationToResults(presentation)
        apply(AttributesGiven(DefaultMsgCodec.toJson(simplifiedProof)))

        retrieveLedgerElementsLegacy(presentation.identifiers, proofRequest.allowsAllSelfAttested) {
          case Success((schemaJson, credDefJson)) =>
            metricsWriter.runWithSpan("processPresentation","PresentProof", InternalSpan) {
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
      case Failure(e) => send(
        Msg.buildProblemReport(s"Invalid presentation -- ${e.getMessage}", invalidPresentation)
      )
    }
  }

  def handleMsgProposePresentationLegacy(msg: Msg.ProposePresentation): Unit = {
    apply(
      ProposeReceived(
        msg.presentation_proposal.attributes.map(_.toEvent),
        msg.presentation_proposal.predicates.map(_.toEvent)
      )
    )
    ctx.signal(Sig.ReviewProposal(msg.presentation_proposal.attributes, msg.presentation_proposal.predicates, msg.comment))
  }


  def _checkRevocationIntervalLegacy(request: ProofRequest): ProofRequest = {
    val interval = request.non_revoked match {
      case Some(i) =>
        if(i.from.isEmpty && i.to.isEmpty) None
        else Some(i)
      case None => None
    }
    request.copy(non_revoked=interval)
  }

  def retrieveLedgerElementsLegacy(identifiers: Seq[Identifier], allowsAllSelfAttested: Boolean=false)
                                  (handler: Try[(String, String)] => Unit): Unit = {
    val ids: mutable.Buffer[(String, String)] = mutable.Buffer()

    identifiers.foreach { identifier =>
      ids.append((identifier.schema_id, identifier.cred_def_id))
    }

    doSchemaAndCredDefRetrievalLegacy(ids.toSet, allowsAllSelfAttested)(handler)
  }

  def doSchemaAndCredDefRetrievalLegacy(ids: Set[(String,String)], allowsAllSelfAttested: Boolean)
                                       (handler: Try[(String, String)] => Unit): Unit = {
    ids.size match {
      case 0 if !allowsAllSelfAttested => Failure(new Exception("No ledger identifiers were included with the Presentation"))
      case _ =>
        doSchemaRetrievalLegacy(ids.map(_._1)) {
          case Success(schema) => doCredDefRetrievalLegacy(schema, ids.map(_._2))(handler)
          case Failure(exception) => handler(Failure(exception))
        }
    }

    def doSchemaRetrievalLegacy(ids: Set[String])(handler: Try[String] => Unit): Unit = {
      ctx.ledger.getSchemas(ids) {
        case Success(schemas) if schemas.size == ids.size =>
          val retrievedSchemasJson = schemas.map { case (id, getSchemaResp) =>
            val schemaJson = DefaultMsgCodec.toJson(getSchemaResp.schema)
            s""""$id": $schemaJson"""
          }.mkString("{", ",", "}")
          handler(Success(retrievedSchemasJson))
        case Success(_) => handler(Failure(new Exception("Unable to retrieve schema from ledger")))
        case Failure(e) => handler(Failure(new Exception("Unable to retrieve schema from ledger", e)))
      }
    }


    def doCredDefRetrievalLegacy(schemas: String, credDefIds: Set[String])
                          (handler: Try[(String, String)] => Unit): Unit = {
      ctx.ledger.getCredDefs(credDefIds) {
        case Success(credDefs) if credDefs.size == ids.size =>
          val retrievedCredDefJson = credDefs.map { case (id, getCredDefResp) =>
            val credDefJson = DefaultMsgCodec.toJson(getCredDefResp.credDef)
            s""""$id": $credDefJson"""
          }.mkString("{", ",", "}")
          handler(Success((schemas, retrievedCredDefJson)))
        case Success(_) => throw new Exception("Unable to retrieve cred def from ledger")
        case Failure(e) => throw new Exception("Unable to retrieve cred def from ledger", e)
      }
    }
  }
}
