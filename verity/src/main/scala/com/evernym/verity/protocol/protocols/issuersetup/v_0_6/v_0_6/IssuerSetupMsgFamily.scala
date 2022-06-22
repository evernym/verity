package com.evernym.verity.protocol.protocols.v_0_6

import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._

object IssuerSetupMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "issuer-setup"
  override val version: MsgFamilyVersion = "0.6"

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map (
    "InitMsg"                   -> classOf[InitMsg],
    "create"                    -> classOf[Create],
    "current-public-identifier" -> classOf[CurrentPublicIdentifier],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map (
    classOf[ProblemReport]            -> "problem-report",
    classOf[PublicIdentifierCreated]  -> "public-identifier-created",
    classOf[PublicIdentifier]         -> "public-identifier"
  )

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map.empty
}


sealed trait Msg extends MsgBase

trait Ctl extends Control with Msg
case class InitMsg(selfId: ParameterValue) extends Ctl
case class Create(ledgerPrefix: String, endorserDID: Option[String]) extends Ctl
case class CurrentPublicIdentifier() extends Ctl
object Ctl  {
}

sealed trait Sig extends Msg
case class PublicIdentifier(did: DidStr, verKey: VerKeyStr, writtenToVDR: Boolean) extends Sig
case class PublicIdentifierCreated(identifier: PublicIdentifier) extends Sig
case class ProblemReport(message: String) extends Sig
object Sig {
}
