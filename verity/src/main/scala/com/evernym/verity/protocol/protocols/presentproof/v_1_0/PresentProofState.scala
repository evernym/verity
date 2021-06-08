package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.protocol.TerminalState
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey

trait Event

case class StateData(
                      requests: List[SegmentKey] = List(),
                      proposals: List[SegmentKey] = List(),
                      presentation: Option[SegmentKey] = None,
                      verificationResults: Option[String] = None,
                      presentationAcknowledged: Boolean = false,
                      agentName: Option[String] = None,
                      logoUrl: Option[String] = None,
                      agencyVerkey: Option[String] = None,
                      publicDid: Option[String] = None
                    ) {


  def addProposal(id: SegmentKey): StateData = {
    copy(proposals = id :: proposals)
  }

  def addRequest(id: SegmentKey): StateData = {
    copy(requests = id :: requests)
  }

  def addPresentation(id: SegmentKey): StateData = {
    copy(presentation = Some(id))
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

trait State
sealed trait HasData {
  def data: StateData
}

object States {
  // Common States
  case class Uninitialized() extends State
  case class Initialized(data: StateData) extends State
  case class ProblemReported(data: StateData, problemDescription: String) extends State with HasData
  case class Rejected(data: StateData, whoRejected: Role, reasonGiven: Option[String]) extends State with HasData

  // Verifier States
  case class ProposalReceived(data: StateData) extends State with HasData
  case class RequestSent(data: StateData) extends State with HasData
  case class Complete(data: StateData) extends State with HasData with TerminalState
  def initRequestSent(id: SegmentKey): RequestSent = RequestSent(StateData(requests = List(id)))
  def initProposalReceived(id: SegmentKey): ProposalReceived = {
    ProposalReceived(StateData().addProposal(id))
  }

  // Prover States
  case class RequestReceived(data: StateData) extends State with HasData
  case class ProposalSent(data: StateData) extends State with HasData
  case class Presented(data: StateData) extends State with HasData with TerminalState
  def initRequestReceived(id: SegmentKey): RequestReceived = {
    RequestReceived(StateData(requests = List(id)))
  }

  def initProposalSent(id: SegmentKey): ProposalSent = {
    ProposalSent(StateData().addProposal(id))
  }
}