package com.evernym.verity.protocol.protocols.issueCredential.v_1_0.legacy

import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred, ProposeCred, RequestCred}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.State._

// TODO: Remove during Ticket=VE-2605
trait StateLegacy {
  case class ProposalSentLegacy(myPwDid: String,
                                theirPwDid: Option[String],
                                credProposed: ProposeCred) extends PostInteractionStarted {
    override def status: String = s"$ProposalSent"
  }

  case class ProposalReceivedLegacy(myPwDid: String,
                                    theirPwDid: Option[String],
                                    credProposed: ProposeCred) extends PostInteractionStarted {
    override def status: String = s"$ProposalReceived"
  }

  case class OfferSentLegacy(myPwDid: String,
                             theirPwDid: Option[String],
                             credOffer: OfferCred,
                             autoIssue: Boolean) extends PostInteractionStarted {
    override def status: String = s"$OfferSent"
  }

  case class OfferReceivedLegacy(myPwDid: String,
                                 theirPwDid: Option[String],
                                 credOffer: OfferCred) extends PostInteractionStarted {
    override def status: String = s"$OfferReceived"
  }

  case class RequestSentLegacy(myPwDid: String,
                               theirPwDid: Option[String],
                               credOffer: OfferCred,
                               credRequest: RequestCred) extends PostInteractionStarted {
    override def status: String = s"$RequestSent"
  }

  case class RequestReceivedLegacy(myPwDid: String,
                                   theirPwDid: Option[String],
                                   credOffer: OfferCred,
                                   credRequest: RequestCred) extends PostInteractionStarted {
    override def status: String =  s"$RequestReceived"
  }

  case class CredSentLegacy(myPwDid: String,
                            theirPwDid: Option[String],
                            credIssued: IssueCred) extends PostInteractionStarted {
    override def status: String = s"$CredSent"
  }

  case class CredReceivedLegacy(myPwDid: String,
                                theirPwDid: Option[String],
                                credIssued: IssueCred) extends PostInteractionStarted {
    override def status: String = s"$CredReceived"
  }

}
