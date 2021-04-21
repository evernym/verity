package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.actor.agent.msghandler.incoming.ProcessRestMsg
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
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
    Should issue credential (basally, accept request-credential)

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
    classOf[SignalMsg.Sent]                   -> "sent",
    classOf[SignalMsg.Received]               -> "received",
    classOf[SignalMsg.AcceptProposal]         -> "accept-proposal",
    classOf[SignalMsg.AcceptOffer]            -> "accept-offer",
    classOf[SignalMsg.AcceptRequest]          -> "accept-request",
    classOf[SignalMsg.ShouldIssue]            -> "should-issue",
    classOf[SignalMsg.StatusReport]           -> "status-report",
    classOf[SignalMsg.ProblemReport]          -> "problem-report",
    classOf[SignalMsg.Ack]                    -> "ack-received",
    classOf[SignalMsg.Invitation]             -> "protocol-invitation",
  )

  override def validateMessage(msg: Any, limit: Int): Boolean = msg match {
        case amw: AgentMsgWrapper   => amw.agentBundledMsg.msgs.map(it => it.msg.length).sum < limit
        case rmp: ProcessRestMsg    => rmp.msg.length() < limit
    }

}

//message objects

case class CredPreviewAttribute(name: String, value: String, `mime-type`: Option[String]=None)
case class CredPreview(`@type`: String, attributes: Vector[CredPreviewAttribute]) {
  def toOption: Option[CredPreview] = Option(this)
}

// Control Messages
trait Ctl extends Control with Msg

object Ctl {

  case class Init(params: Parameters) extends Ctl

  case class Reject(comment: Option[String]=Some("")) extends Ctl

  case class Status() extends Ctl

  case class AttachedOffer(offer: OfferCred) extends Ctl {
    override def validate(): Unit = {
      checkRequired("offer", offer)
    }
  }

  case class Propose(cred_def_id: String,
                     credential_values: Map[String, String],
                     comment: Option[String]=Some("")) extends Ctl {
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
                  ) extends Ctl {
    override def validate(): Unit = {
      checkRequired("cred_def_id", cred_def_id)
      checkRequired("credential_values", credential_values)
    }
  }

  case class Request(cred_def_id: String,
                     comment: Option[String]=Some("")) extends Ctl {
    override def validate(): Unit = {
      checkRequired("cred_def_id", cred_def_id)
    }
  }

  case class Issue(revRegistryId: Option[String]=None,
                   comment: Option[String]=Some(""),
                   `~please_ack`: Option[PleaseAck]=None) extends Ctl
}

//signal messages
sealed trait SignalMsg
object SignalMsg {
  case class Sent(msg: Any) extends SignalMsg
  case class Invitation(inviteURL: String, shortInviteURL: Option[String], invitationId: String) extends SignalMsg
  case class Received(msg: Any) extends SignalMsg
  case class AcceptProposal(proposal: ProposeCred) extends SignalMsg
  case class AcceptOffer(offer: OfferCred) extends SignalMsg
  case class AcceptRequest(request: RequestCred) extends SignalMsg
  case class ShouldIssue(requestCred: RequestCred) extends SignalMsg
  case class StatusReport(status: String) extends SignalMsg
  case class Ack(status: String) extends SignalMsg
  case class ProblemReport(description: ProblemDescription) extends AdoptableProblemReport with SignalMsg
  def buildProblemReport(description: String, code: String): SignalMsg.ProblemReport = {
    SignalMsg.ProblemReport(
      ProblemDescription(
        Some(description),
        code
      )
    )
  }
}

trait Msg extends MsgBase

//protocol messages
object Msg {
  case class ProposeCred(cred_def_id: String,
                         credential_proposal: Option[CredPreview]=None,
                         comment: Option[String]=Some("")) extends Msg {
    checkRequired("cred_def_id", cred_def_id)
  }

  case class OfferCred(credential_preview: CredPreview,
                       `offers~attach`: Vector[AttachmentDescriptor],
                       comment: Option[String]=Some(""),
                       price: Option[String]=None) extends Msg {
    override def validate(): Unit = {
      checkRequired("credential_preview", credential_preview)
      checkRequired("offers~attach", `offers~attach`, allowEmpty = true)
    }
  }

  case class RequestCred(`requests~attach`: Vector[AttachmentDescriptor],
                         comment: Option[String]=Some("")) extends Msg {
    override def validate(): Unit = {
      checkRequired("requests~attach", `requests~attach`, allowEmpty = true)
    }
  }

  case class IssueCred(`credentials~attach`: Vector[AttachmentDescriptor],
                       comment: Option[String]=Some(""),
                       `~please_ack`: Option[PleaseAck]=None) extends Msg {
    override def validate(): Unit = {
      checkRequired("credentials~attach", `credentials~attach`, allowEmpty = true)
    }
  }

  case class Ack(status: String) extends Msg with AdoptableAck {
    override def validate(): Unit = {
      checkRequired("status", `status`, allowEmpty = true)
    }
  }

  case class ProblemReport(description: ProblemDescription, override val comment: Option[String] = None)
    extends Msg
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




