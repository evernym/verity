package com.evernym.verity.protocol.protocols.tokenizer

import com.evernym.verity.Base64Encoded
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.actor.ServiceDecorator
import com.evernym.verity.protocol.engine.Constants.{MFV_0_1, MSG_FAMILY_TOKEN_PROVISIONING}
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.DbcUtil.requireNotNull
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.ProvisioningToken
import com.evernym.verity.protocol.protocols.tokenizer.{Token => TokenEvt}
import com.evernym.verity.util.TimeUtil.IsoDateTime

object TokenizerMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = MSG_FAMILY_TOKEN_PROVISIONING
  override val version: MsgFamilyVersion = MFV_0_1

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "get-token"  -> classOf[GetToken],
    "send-token"  -> classOf[Token],
    "problem-report" -> classOf[ProblemReport],
    "push-token" -> classOf[PushToken]
  )
  override val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map (
    "ask-for-token"                  -> classOf[AskForToken],
  )
  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
  )

  sealed trait Role
  object Requester extends Role
  object Tokenizer extends Role

  /**
    * Common Types
    */

  /**
    * Messages used in this protocol for Token Provisioning
    * Types of messages are from the perspective of the 'sender' of the message
    */
  sealed trait Msg extends MsgBase
  //TODO: when the service decorator functionality is actually implemented,
  // the PushToken will be abstracted from the Token Protocol
  // somewhere in the engine or in the container the decorator will be handled - probably engine
  case class PushToken(msg: Token, deliveryMethod: ComMethodDetail) extends ServiceDecorator with Msg

  case class GetToken(sponseeId: String, sponsorId: String, pushId: ComMethodDetail) extends Msg

  case class Token(sponseeId: String,
                   sponsorId: String,
                   nonce: Nonce,
                   timestamp: IsoDateTime,
                   sig: Base64Encoded,
                   sponsorVerKey: VerKey) extends Msg with ProvisioningToken {
    def asEvent: TokenEvt = TokenEvt(
      requireNotNull(sponseeId, "sponseeId"),
      requireNotNull(sponsorId, "sponsorId"),
      requireNotNull(nonce, "nonce"),
      requireNotNull(timestamp, "timestamp"),
      requireNotNull(sig, "sig"),
      requireNotNull(sponsorVerKey, "sponsorVerKey")
    )
  }
  case class ProblemReport(msg: String=DefaultProblem.err)       extends Msg


  /**
    * Control messages
    */
  sealed trait Ctl extends Control with MsgBase
  case class AskForToken(sponseeId: String, sponsorId: String, pushId: ComMethodDetail) extends Ctl {
    def asGetToken(): GetToken = GetToken(sponseeId, sponsorId, pushId)
  }

  /**
    * Errors
    */
  val DEFAULT_PROBLEM_REPORT: String = "Error creating agent"
  trait TokenizingErr   extends Exception {
    def err: String
  }

  case object InvalidTokenErr extends TokenizingErr {
    def err = "Token is invalid"
  }

  case object SigningTokenErr extends TokenizingErr {
    def err = "Tokenizer failed signing token"
  }

  case object DefaultProblem extends TokenizingErr {
    def err = "Error generating token"
  }

  /**
    * Driver Messages
    */
  sealed trait Signal
}
