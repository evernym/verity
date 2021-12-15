package com.evernym.verity.protocol.protocols.presentproof.v_1_0.legacy

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.{AttributesPresented, PresentationPreview, PresentationPreviewAttribute, PresentationPreviewPredicate, PreviewAttribute, PreviewPredicate, ProofPresentation, ProofRequest, Role, State}

case class StateDataLegacy(requests: List[ProofRequest] = List(),
                           proposals: List[PresentationPreview] = List(),
                           presentation: Option[ProofPresentation] = None,
                           presentedAttributes: Option[AttributesPresented] = None,
                           verificationResults: Option[String] = None,
                           presentationAcknowledged: Boolean = false,
                           agentName: Option[String] = None,
                           logoUrl: Option[String] = None,
                           agencyVerkey: Option[String] = None,
                           publicDid: Option[String] = None) {

  def addRequest(request: String): StateDataLegacy = {
    addRequest(DefaultMsgCodec.fromJson[ProofRequest](request))
  }

  def addRequest(request: ProofRequest): StateDataLegacy = {
    copy(requests = request :: requests)
  }

  def addProposal(attrs: Seq[PreviewAttribute], preds: Seq[PreviewPredicate]): StateDataLegacy = {
    addProposal(PresentationPreview(
      attrs.map(a => fromEvent(a)),
      preds.map(p => fromEvent(p)),
    ))
  }

  def addProposal(proposal: PresentationPreview): StateDataLegacy = {
    copy(proposals = proposal :: proposals)
  }

  def addPresentation(presentation: String): StateDataLegacy = {
    copy(presentation = Some(DefaultMsgCodec.fromJson[ProofPresentation](presentation)))
  }

  def addAttributesPresented(given: String): StateDataLegacy = {
    copy(presentedAttributes = Some(DefaultMsgCodec.fromJson[AttributesPresented](given)))
  }

  def addVerificationResults(results: String): StateDataLegacy = {
    copy(verificationResults = Some(results))
  }

  def addAck(status: String): StateDataLegacy = {
    val wasAcknowledged = status.toUpperCase() match {
      case "OK" => true
      case _ => false
    }
    copy(presentationAcknowledged = wasAcknowledged)
  }

  def fromEvent(pa: PreviewAttribute): PresentationPreviewAttribute = {
    PresentationPreviewAttribute(
      pa.name,
      pa.credDefId.headOption,
      pa.mimeType.headOption,
      pa.value.headOption,
      pa.referent.headOption
    )
  }

  def fromEvent(pp: PreviewPredicate): PresentationPreviewPredicate = {
    PresentationPreviewPredicate(
      pp.name,
      pp.credDefId,
      pp.predicate,
      pp.threshold
    )
  }
}

sealed trait HasData {
  def data: StateDataLegacy
}

object StatesLegacy {
  // Common States
  case class ProblemReported(data: StateDataLegacy, problemDescription: String) extends State with HasData
  case class Rejected(data: StateDataLegacy, whoRejected: Role, reasonGiven: Option[String]) extends State with HasData

  // Verifier States
  case class ProposalReceived(data: StateDataLegacy) extends State with HasData
  case class RequestSent(data: StateDataLegacy) extends State with HasData
  case class Complete(data: StateDataLegacy) extends State with HasData
  def initRequestSent(requestStr: String): RequestSent = {
    val req = DefaultMsgCodec.fromJson[ProofRequest](requestStr)
    RequestSent(StateDataLegacy(requests = List(req)))
  }
  def initProposalReceived(attrs: Seq[PreviewAttribute], preds: Seq[PreviewPredicate]): ProposalReceived = {
    ProposalReceived(StateDataLegacy().addProposal(attrs, preds))
  }

  // Prover States
  case class RequestReceived(data: StateDataLegacy) extends State with HasData
  case class ProposalSent(data: StateDataLegacy) extends State with HasData
  case class Presented(data: StateDataLegacy) extends State with HasData
  def initRequestReceived(requestStr: String): RequestReceived = {
    val req = DefaultMsgCodec.fromJson[ProofRequest](requestStr)
    RequestReceived(StateDataLegacy(requests = List(req)))
  }
  def initProposalSent(attrs: Seq[PreviewAttribute], preds: Seq[PreviewPredicate]): ProposalSent = {
    ProposalSent(StateDataLegacy().addProposal(attrs, preds))
  }
}
