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
    def theirPwDid: Option[String]
  }

  trait PostInteractionStarted extends HasMyAndTheirDid

  case class Initialized(myPwDid: String,
                         theirPwDid: Option[String],
                         agentName: Option[String],
                         logoUrl: Option[String],
                         agencyVerkey: Option[String],
                         publicDid: Option[String]
                        ) extends HasMyAndTheirDid {
    override def status: String = "Initialized"
  }

  case class ProposalSent(myPwDid: String,
                          theirPwDid: Option[String],
                          credProposed: ProposeCred) extends PostInteractionStarted {
    override def status: String = "ProposalSent"
  }
  case class ProposalReceived(myPwDid: String,
                              theirPwDid: Option[String],
                              credProposed: ProposeCred) extends PostInteractionStarted {
    override def status: String = "ProposalReceived"
  }

  case class OfferSent(myPwDid: String,
                       theirPwDid: Option[String],
                       credOffer: OfferCred,
                       autoIssue: Boolean) extends PostInteractionStarted {
    override def status: String = "OfferSent"
  }

  case class OfferReceived(myPwDid: String,
                           theirPwDid: Option[String],
                           credOffer: OfferCred) extends PostInteractionStarted {
    override def status: String = "OfferReceived"
  }

  case class RequestSent(myPwDid: String,
                         theirPwDid: Option[String],
                         credOffer: OfferCred,
                         credRequest: RequestCred) extends PostInteractionStarted  {
    override def status: String = "RequestSent"
  }

  case class RequestReceived(myPwDid: String,
                             theirPwDid: Option[String],
                             credOffer: OfferCred,
                             credRequest: RequestCred) extends PostInteractionStarted {
    override def status: String = "RequestReceived"
  }

  case class IssueCredSent(myPwDid: String,
                           theirPwDid: Option[String],
                           credIssued: IssueCred) extends PostInteractionStarted {
    override def status: String = "CredSent"
  }
  case class IssueCredReceived(myPwDid: String,
                               theirPwDid: Option[String],
                               credIssued: IssueCred) extends PostInteractionStarted  {
    override def status: String = "CredReceived"
  }

  case class Rejected(comment: Option[String]=Some("")) extends State {
    override def status: String = "Rejected"
  }

  case class ProblemReported(description: String) extends State {
    override def status: String = "ProblemReported"
  }
}