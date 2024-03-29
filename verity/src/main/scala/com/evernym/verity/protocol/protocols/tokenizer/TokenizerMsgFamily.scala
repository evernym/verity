package com.evernym.verity.protocol.protocols.tokenizer

import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.DbcUtil.requireNotNull
import com.evernym.verity.protocol.protocols.agentprovisioning.v_0_7.AgentProvisioningMsgFamily.ProvisioningToken
import com.evernym.verity.protocol.protocols.tokenizer.{Token => TokenEvt}
import com.evernym.verity.util.TimeUtil.IsoDateTime
import com.evernym.verity.util2.Base64Encoded

object TokenizerMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "token-provisioning"
  override val version: MsgFamilyVersion = "0.1"

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "get-token"       -> classOf[GetToken],
    "send-token"      -> classOf[Token],
    "problem-report"  -> classOf[ProblemReport],
  )

  override val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map (
    "ask-for-token"   -> classOf[AskForToken],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map()

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

  case class GetToken(sponseeId: String, sponsorId: String, pushId: Option[Any]=None) extends Msg

  case class Token(sponseeId: String,
                   sponsorId: String,
                   nonce: Nonce,
                   timestamp: IsoDateTime,
                   sig: Base64Encoded,
                   sponsorVerKey: VerKeyStr) extends Msg with ProvisioningToken {
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
  case class AskForToken(sponseeId: String, sponsorId: String, pushId: Option[Any]=None) extends Ctl {
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
