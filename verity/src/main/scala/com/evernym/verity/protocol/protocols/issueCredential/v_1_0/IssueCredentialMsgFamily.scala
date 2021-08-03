package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.didcomm.decorators.{AttachmentDescriptor, PleaseAck}
import com.evernym.verity.protocol.didcomm.messages.{AdoptableAck, AdoptableProblemReport, ProblemDescription}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.InviteShortened
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.Init
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg._


object IssueCredMsgFamily
  extends MsgFamily {

  override val qualifier: MsgFamilyQualifier = MsgFamily.COMMUNITY_QUALIFIER
  override val name: MsgFamilyName = "issue-credential"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "propose-credential"                -> classOf[ProposeCred],
    "offer-credential"                  -> classOf[OfferCred],
    "request-credential"                -> classOf[RequestCred],
    "issue-credential"                  -> classOf[IssueCred],
    "problem-report"                    -> classOf[ProblemReport],
    "ack"                               -> classOf[Ack]
  )

/*
  Issuer has two decision points:
    To accept propose-credential (but only if Holder starts protocol or cycle of negotiation
    Should issue credential (basically, accept request-credential)

  Holder has only one decision point:
    To accept offer-credential
 */
  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "Init"                              -> classOf[Init],
    "offer-invitation"                  -> classOf[Ctl.AttachedOffer],
    "propose"                           -> classOf[Ctl.Propose],
    "offer"                             -> classOf[Ctl.Offer],
    "request"                           -> classOf[Ctl.Request],
    "issue"                             -> classOf[Ctl.Issue],
    "reject"                            -> classOf[Ctl.Reject],
    "status"                            -> classOf[Ctl.Status],
    "invite-shortened"                  -> classOf[InviteShortened],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Sig.Sent]                   -> "sent",
    classOf[Sig.Received]               -> "received",
    classOf[Sig.AcceptProposal]         -> "accept-proposal",
    classOf[Sig.AcceptOffer]            -> "accept-offer",
    classOf[Sig.AcceptRequest]          -> "accept-request",
    classOf[Sig.ShouldIssue]            -> "should-issue",
    classOf[Sig.StatusReport]           -> "status-report",
    classOf[Sig.ProblemReport]          -> "problem-report",
    classOf[Sig.Ack]                    -> "ack-received",
    classOf[Sig.Invitation]             -> "protocol-invitation",
  )

}

//message objects

case class CredPreviewAttribute(name: String, value: String, `mime-type`: Option[String]=None)
case class CredPreview(`@type`: String, attributes: Vector[CredPreviewAttribute]) {
  def toOption: Option[CredPreview] = Option(this)

  def toCredPreviewObject: CredPreviewObject = {
    CredPreviewObject(
      `@type`,
      attributes.map(a => CredPreviewAttr(a.name, a.value, a.`mime-type`))
    )
  }
}

// Control Messages
trait CtlMsg extends Control with MsgBase
object Ctl {

  case class Init(params: Parameters) extends CtlMsg

  case class Reject(comment: Option[String]=Some("")) extends CtlMsg

  case class Status() extends CtlMsg

  case class AttachedOffer(offer: OfferCred) extends CtlMsg {
    override def validate(): Unit = {
      checkRequired("offer", offer)
    }
  }

  case class Propose(cred_def_id: String,
                     credential_values: Map[String, String],
                     comment: Option[String]=Some("")) extends CtlMsg {
    override def validate(): Unit = {
      checkRequired("cred_def_id", cred_def_id)
      checkRequired("credential_values", credential_values)
    }
  }

  case class Offer(cred_def_id: String,
                   credential_values: Map[String, String],
                   price: Option[String]=None,
                   comment: Option[String]=Some(""),
                   auto_issue: Option[Boolean]=None,
                   by_invitation: Option[Boolean]=None,
                  ) extends CtlMsg {
    override def validate(): Unit = {
      checkRequired("cred_def_id", cred_def_id)
      checkRequired("credential_values", credential_values)
      checkIfValidBooleanData("auto_issue", auto_issue)
      checkIfValidBooleanData("by_invitation", by_invitation)
    }
  }

  case class Request(cred_def_id: String,
                     comment: Option[String]=Some("")) extends CtlMsg {
    override def validate(): Unit = {
      checkRequired("cred_def_id", cred_def_id)
    }
  }

  case class Issue(revRegistryId: Option[String]=None,
                   comment: Option[String]=Some(""),
                   `~please_ack`: Option[PleaseAck]=None) extends CtlMsg
}

//signal messages
sealed trait SigMsg
object Sig {
  case class Sent(msg: Any) extends SigMsg
  case class Invitation(inviteURL: String, shortInviteURL: Option[String], invitationId: String) extends SigMsg
  case class Received(msg: Any) extends SigMsg
  case class AcceptProposal(proposal: ProposeCred) extends SigMsg
  case class AcceptOffer(offer: OfferCred) extends SigMsg
  case class AcceptRequest(request: RequestCred) extends SigMsg
  case class ShouldIssue(requestCred: RequestCred) extends SigMsg
  case class StatusReport(status: String) extends SigMsg
  case class Ack(status: String) extends SigMsg
  case class ProblemReport(description: ProblemDescription) extends AdoptableProblemReport with SigMsg
  def buildProblemReport(description: String, code: String): Sig.ProblemReport = {
    Sig.ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }
}

//protocol messages
trait ProtoMsg extends MsgBase
object Msg {
  case class ProposeCred(cred_def_id: String,
                         credential_proposal: Option[CredPreview]=None,
                         comment: Option[String]=Some("")) extends ProtoMsg {
    checkRequired("cred_def_id", cred_def_id)
  }

  case class OfferCred(credential_preview: CredPreview,
                       `offers~attach`: Vector[AttachmentDescriptor],
                       comment: Option[String]=Some(""),
                       price: Option[String]=None) extends ProtoMsg {
    override def validate(): Unit = {
      checkRequired("credential_preview", credential_preview)
      checkRequired("offers~attach", `offers~attach`, allowEmpty = true)
    }
  }

  case class RequestCred(`requests~attach`: Vector[AttachmentDescriptor],
                         comment: Option[String]=Some("")) extends ProtoMsg {
    override def validate(): Unit = {
      checkRequired("requests~attach", `requests~attach`, allowEmpty = true)
    }
  }

  case class IssueCred(`credentials~attach`: Vector[AttachmentDescriptor],
                       comment: Option[String]=Some(""),
                       `~please_ack`: Option[PleaseAck]=None) extends ProtoMsg {
    override def validate(): Unit = {
      checkRequired("credentials~attach", `credentials~attach`, allowEmpty = true)
    }
  }

  case class Ack(status: String) extends ProtoMsg with AdoptableAck {
    override def validate(): Unit = {
      checkRequired("status", `status`, allowEmpty = true)
    }
  }

  case class ProblemReport(description: ProblemDescription, override val comment: Option[String] = None)
    extends ProtoMsg
      with AdoptableProblemReport

  def buildProblemReport(description: String, code: String): Msg.ProblemReport = {
    Msg.ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }
}




