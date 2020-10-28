package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.agentmsg.DefaultMsgCodec

trait Event

case class StateData(requests: List[ProofRequest] = List(),
                     proposals: List[PresentationPreview] = List(),
                     presentation: Option[ProofPresentation] = None,
                     presentedAttributes: Option[AttributesPresented] = None,
                     verificationResults: Option[String] = None,
                     presentationAcknowledged: Boolean = false) {

  def addRequest(request: String): StateData = {
    addRequest(DefaultMsgCodec.fromJson[ProofRequest](request))
  }

  def addRequest(request: ProofRequest): StateData = {
    copy(requests = request :: requests)
  }

  def addProposal(attrs: Seq[PreviewAttribute], preds: Seq[PreviewPredicate]): StateData = {
    addProposal(PresentationPreview(
      attrs.map(a => fromEvent(a)),
      preds.map(p => fromEvent(p)),
    ))
  }

  def addProposal(proposal: PresentationPreview): StateData = {
    copy(proposals = proposal :: proposals)
  }

  def addPresentation(presentation: String): StateData = {
    copy(presentation = Some(DefaultMsgCodec.fromJson[ProofPresentation](presentation)))
  }

  def addAttributesPresented(given: String): StateData = {
    copy(presentedAttributes = Some(DefaultMsgCodec.fromJson[AttributesPresented](given)))
  }

  def addVerificationResults(results: String): StateData = {
    copy(verificationResults = Some(results))
  }

  def addAck(status: String): StateData = {
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

sealed trait State
sealed trait HasData {
  def data: StateData
}

object States {
  // Common States
  case class Uninitialized() extends State
  case class Initialized() extends State
  case class ProblemReported(data: StateData, problemDescription: String) extends State with HasData
  case class Rejected(data: StateData, whoRejected: Role, reasonGiven: Option[String]) extends State with HasData

  // Verifier States
  case class ProposalReceived(data: StateData) extends State with HasData
  case class RequestSent(data: StateData) extends State with HasData
  case class Complete(data: StateData) extends State with HasData
  def initRequestSent(requestStr: String): RequestSent = {
    val req = DefaultMsgCodec.fromJson[ProofRequest](requestStr)
    RequestSent(StateData(requests = List(req)))
  }
  def initProposalReceived(attrs: Seq[PreviewAttribute], preds: Seq[PreviewPredicate]): ProposalReceived = {
    ProposalReceived(StateData().addProposal(attrs, preds))
  }

  // Prover States
  case class RequestReceived(data: StateData) extends State with HasData
  case class ProposalSent(data: StateData) extends State with HasData
  case class Presented(data: StateData) extends State with HasData
  def initRequestReceived(requestStr: String): RequestReceived = {
    val req = DefaultMsgCodec.fromJson[ProofRequest](requestStr)
    RequestReceived(StateData(requests = List(req)))
  }
  def initProposalSent(attrs: Seq[PreviewAttribute], preds: Seq[PreviewPredicate]): ProposalSent = {
    ProposalSent(StateData().addProposal(attrs, preds))
  }
}