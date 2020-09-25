package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.decorators.EmbeddingAttachment
import com.evernym.verity.protocol.didcomm.messages.{AdoptableAck, AdoptableProblemReport, ProblemDescription}
import com.evernym.verity.protocol.engine._

object PresentProofMsgFamily
  extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.COMMUNITY_QUALIFIER
  override val name: MsgFamilyName = "present-proof"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "propose-presentation"     -> classOf[Msg.ProposePresentation],
    "request-presentation"     -> classOf[Msg.RequestPresentation],
    "presentation"             -> classOf[Msg.Presentation],
    "ack"                      -> classOf[Msg.Ack],
    "problem-report"           -> classOf[Msg.ProblemReport]
  )

  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "Init"              -> classOf[Ctl.Init],
    "request"           -> classOf[Ctl.Request],
    "present"           -> classOf[Ctl.AcceptRequest],
//    "accept-proposal"   -> classOf[Ctl.AcceptProposal],
//    "propose"           -> classOf[Ctl.Propose],
    "reject"            -> classOf[Ctl.Reject],
    "status"            -> classOf[Ctl.Status],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
//    classOf[_]          -> "sent",
//    classOf[_]          -> "received",
    classOf[Sig.ReviewRequest]        -> "review-request",
    classOf[Sig.ReviewProposal]       -> "review-proposal",
    classOf[Sig.PresentationResult]   -> "presentation-result",
    classOf[Sig.ProblemReport]        -> "problem-report",
    classOf[Sig.StatusReport]         -> "status-report",
  )
}

// Protocol Messages
sealed trait ProtoMsg extends MsgBase

package object Msg {
  case class ProposePresentation(comment: String = "") extends ProtoMsg
  case class RequestPresentation(comment: String = "",
                                 `request_presentations~attach`: Seq[EmbeddingAttachment]) extends ProtoMsg
  case class Presentation(comment: String = "",
                          `presentations~attach`: Seq[EmbeddingAttachment]) extends ProtoMsg
  case class Ack(status: String) extends AdoptableAck with ProtoMsg
  case class ProblemReport(description: ProblemDescription, override val comment: Option[String] = None)
    extends AdoptableProblemReport
      with ProtoMsg

  def buildProblemReport(description: String, code: String): Msg.ProblemReport = {
    Msg.ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }
}

// Control Messages
sealed trait CtlMsg extends Control with MsgBase
package object Ctl {
  case class Init(selfId: ParameterValue, otherId: ParameterValue) extends CtlMsg
  case class Request(name: String,
                     proof_attrs: Option[List[ProofAttribute]],
                     proof_predicates: Option[List[ProofPredicate]],
                     revocation_interval: Option[RevocationInterval]) extends CtlMsg
  case class AcceptRequest(selfAttestedAttrs: Map[String, String]=Map.empty) extends CtlMsg
//  case class AcceptProposal() extends CtlMsg
//  case class Propose() extends CtlMsg
  case class Reject(reason: Option[String]) extends CtlMsg
  case class Status() extends CtlMsg

}

// Signal Messages
sealed trait SigMsg
case class Problem(code: Int, error: String)
package object Sig {
  case class ReviewRequest(proof_request: ProofRequest, can_fulfill: Boolean) extends SigMsg
  case class ReviewProposal() extends SigMsg
  case class PresentationResult(verification_result: String, requested_presentation: AttributesPresented) extends SigMsg
  case class ProblemReport(description: ProblemDescription) extends AdoptableProblemReport with SigMsg
  case class StatusReport(status: String, results: Option[PresentationResult], error: Option[Problem])

  def proofRequestToReviewRequest(requestStr: String, canFulfill: Boolean) = {
    val req = DefaultMsgCodec.fromJson[ProofRequest](requestStr)
    ReviewRequest(req, canFulfill)
  }

  def buildProblemReport(description: String, code: String): Sig.ProblemReport = {
    Sig.ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }
}