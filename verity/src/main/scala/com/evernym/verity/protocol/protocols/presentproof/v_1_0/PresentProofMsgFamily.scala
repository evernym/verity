package com.evernym.verity.protocol.protocols.presentproof.v_1_0

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor
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
    "Init"               -> classOf[Ctl.Init],
    "request-invitation" -> classOf[Ctl.AttachedRequest],
    "request"            -> classOf[Ctl.Request],
    "present"            -> classOf[Ctl.AcceptRequest],
    "accept-proposal"    -> classOf[Ctl.AcceptProposal],
    "propose"            -> classOf[Ctl.Propose],
    "reject"             -> classOf[Ctl.Reject],
    "status"             -> classOf[Ctl.Status],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Sig.ReviewRequest]        -> "review-request",
    classOf[Sig.ReviewProposal]       -> "review-proposal",
    classOf[Sig.PresentationResult]   -> "presentation-result",
    classOf[Sig.ProblemReport]        -> "problem-report",
    classOf[Sig.StatusReport]         -> "status-report",
    classOf[Sig.Invitation]             -> "protocol-invitation"
  )
}

case class PresentationPreviewAttribute(name: String,
                                        cred_def_id: Option[String],
                                        `mime-type`: Option[String],
                                        value: Option[String],
                                        referent: Option[String])
case class PresentationPreviewPredicate(name: String,
                                        cred_def_id: String,
                                        predicate: String,
                                        threshold: String)
case class PresentationPreview(attributes: Seq[PresentationPreviewAttribute],
                               predicates: Seq[PresentationPreviewPredicate],
                               `@type`: String = "https://didcomm.org/present-proof/1.0/presentation-preview")

// Protocol Messages
sealed trait ProtoMsg extends MsgBase

package object Msg {
  case class ProposePresentation(comment: String = "", presentation_proposal: PresentationPreview) extends ProtoMsg
  case class RequestPresentation(comment: String = "",
                                 `request_presentations~attach`: Seq[AttachmentDescriptor]) extends ProtoMsg
  case class Presentation(comment: String = "",
                          `presentations~attach`: Seq[AttachmentDescriptor]) extends ProtoMsg
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
  case class Init(selfId: ParameterValue,
                  otherId: ParameterValue,
                  agentName: Option[String],
                  logoUrl: Option[String],
                  agencyVerkey: Option[String],
                  publicDid: Option[String]
                 ) extends CtlMsg
  case class AttachedRequest(request: Msg.RequestPresentation) extends CtlMsg
  case class Request(name: String,
                     proof_attrs: Option[List[ProofAttribute]],
                     proof_predicates: Option[List[ProofPredicate]],
                     revocation_interval: Option[RevocationInterval],
                     by_invitation: Option[Boolean]=None,
                    ) extends CtlMsg
  case class AcceptRequest(selfAttestedAttrs: Map[String, String]=Map.empty) extends CtlMsg
  case class AcceptProposal() extends CtlMsg
  case class Propose(attributes: Option[List[PresentationPreviewAttribute]],
                     predicates: Option[List[PresentationPreviewPredicate]],
                     comment: String) extends CtlMsg
  case class Reject(reason: Option[String]) extends CtlMsg
  case class Status() extends CtlMsg

}

// Signal Messages
sealed trait SigMsg
case class Problem(code: Int, error: String)
package object Sig {
  case class Invitation(inviteURL: String, shortInviteURL: Option[String], invitationId: String) extends SigMsg
  case class ReviewRequest(proof_request: ProofRequest, can_fulfill: Boolean) extends SigMsg
  case class ReviewProposal(attributes: Seq[PresentationPreviewAttribute],
                            predicates: Seq[PresentationPreviewPredicate],
                            comment: String) extends SigMsg
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