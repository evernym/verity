package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred, ProposeCred, RequestCred}

trait Event

sealed trait State {
  def status: String
}
object State {
  case class Uninitialized() extends State {
    override def status: String = "Uninitialized"
  }

  trait HasMyAndTheirDid extends State {
    def myPwDid: String
    def theirPwDid: String
  }

  trait PostInteractionStarted extends HasMyAndTheirDid

  case class Initialized(myPwDid: String, theirPwDid: String) extends HasMyAndTheirDid {
    override def status: String = "Initialized"
  }

  case class ProposalSent(myPwDid: String, theirPwDid: String, credProposed: ProposeCred) extends PostInteractionStarted {
    override def status: String = "ProposalSent"
  }
  case class ProposalReceived(myPwDid: String, theirPwDid: String, credProposed: ProposeCred) extends PostInteractionStarted {
    override def status: String = "ProposalReceived"
  }

  case class OfferSent(myPwDid: String, theirPwDid: String, credOffer: OfferCred, autoIssue: Boolean) extends PostInteractionStarted {
    override def status: String = "OfferSent"
  }

  case class OfferReceived(myPwDid: String, theirPwDid: String, credOffer: OfferCred) extends PostInteractionStarted {
    override def status: String = "OfferReceived"
  }

  case class RequestSent(myPwDid: String, theirPwDid: String,
                         credOffer: OfferCred,
                         credRequest: RequestCred) extends PostInteractionStarted  {
    override def status: String = "RequestSent"
  }

  case class RequestReceived(myPwDid: String, theirPwDid: String,
                             credOffer: OfferCred,
                             credRequest: RequestCred) extends PostInteractionStarted {
    override def status: String = "RequestReceived"
  }

  case class IssueCredSent(myPwDid: String, theirPwDid: String, credIssued: IssueCred) extends PostInteractionStarted {
    override def status: String = "CredSent"
  }
  case class IssueCredReceived(myPwDid: String, theirPwDid: String, credIssued: IssueCred) extends PostInteractionStarted  {
    override def status: String = "CredReceived"
  }

  case class Rejected(comment: Option[String]=Some("")) extends State {
    override def status: String = "Rejected"
  }

  case class ProblemReported(description: String) extends State {
    override def status: String = "ProblemReported"
  }
}