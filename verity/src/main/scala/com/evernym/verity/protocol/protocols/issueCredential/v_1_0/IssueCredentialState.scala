package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred, ProposeCred, RequestCred}

trait Event

sealed trait State {
  def status: String = this.getClass.getSimpleName
}

object State {
  case class Uninitialized() extends State

  trait HasMyAndTheirDid extends State {
    def myPwDid: String
    def theirPwDid: Option[String]
  }

  trait PostInteractionStarted extends HasMyAndTheirDid

  trait PostInteractionSnapshottable extends PostInteractionStarted

  case class Initialized(myPwDid: String,
                         theirPwDid: Option[String],
                         agentName: Option[String],
                         logoUrl: Option[String],
                         agencyVerkey: Option[String],
                         publicDid: Option[String]
                        ) extends HasMyAndTheirDid

  case class ProposalSent(myPwDid: String,
                          theirPwDid: Option[String],
                          credProposed: ProposeCred) extends PostInteractionStarted

  case class ProposalReceived(myPwDid: String,
                              theirPwDid: Option[String],
                              credProposed: ProposeCred) extends PostInteractionStarted

  case class OfferSent(myPwDid: String,
                       theirPwDid: Option[String],
                       credOffer: OfferCred,
                       autoIssue: Boolean) extends PostInteractionStarted

  case class OfferReceived(myPwDid: String,
                           theirPwDid: Option[String],
                           credOffer: OfferCred) extends PostInteractionStarted

  case class RequestSent(myPwDid: String,
                         theirPwDid: Option[String],
                         credOffer: OfferCred,
                         credRequest: RequestCred) extends PostInteractionStarted

  case class RequestReceived(myPwDid: String,
                             theirPwDid: Option[String],
                             credOffer: OfferCred,
                             credRequest: RequestCred) extends PostInteractionStarted

  case class CredSent(myPwDid: String,
                      theirPwDid: Option[String],
                      credIssued: IssueCred) extends PostInteractionStarted

  case class CredReceived(myPwDid: String,
                          theirPwDid: Option[String],
                          credIssued: IssueCred) extends PostInteractionStarted

  case class Rejected(comment: Option[String]=Some("")) extends State

  case class ProblemReported(description: String) extends State
}