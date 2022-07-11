package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.validate.ValidateHelper.{checkOptionalNotEmpty, checkRequired, checkValidDID}
import com.evernym.verity.protocol.protocols.{ledgerPrefixStr, ledgerRequestStr}

object IssuerSetupMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "issuer-setup"
  override val version: MsgFamilyVersion = "0.7"

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map (
    "InitMsg"                   -> classOf[InitMsg],
    "create"                    -> classOf[Create],
    "current-public-identifier" -> classOf[CurrentPublicIdentifier],
    "endorsement-result"        -> classOf[EndorsementResult],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map (
    classOf[ProblemReport]            -> "problem-report",
    classOf[PublicIdentifierCreated]  -> "public-identifier-created",
    classOf[PublicIdentifier]         -> "public-identifier",
  )

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map.empty
}


sealed trait Msg extends MsgBase

trait IssuerSetupControl extends Control with MsgBase
case class InitMsg(selfId: ParameterValue) extends IssuerSetupControl
case class Create(ledgerPrefix: String, endorserDID: Option[String]) extends Msg with IssuerSetupControl {
  override def validate(): Unit = {
    checkRequired("ledgerPrefix", ledgerPrefix)
    checkOptionalNotEmpty("endorserDID", endorserDID)
    endorserDID.foreach{ endorser =>
      checkValidDID("endorserDID", endorser)
    }
  }
}
case class CurrentPublicIdentifier() extends IssuerSetupControl
case class EndorsementResult(code: String, description: String) extends IssuerSetupControl

sealed trait Sig extends Msg
case class PublicIdentifier(did: DidStr, verKey: VerKeyStr) extends Sig
case class PublicIdentifierCreated(identifier: PublicIdentifier, status: EndorsementStatus) extends Sig
case class ProblemReport(message: String) extends Sig

sealed trait EndorsementStatus
case class NeedsEndorsement(needsEndorsement: ledgerRequestStr) extends Sig with EndorsementStatus
case class WrittenToLedger(writtenToLedger: ledgerPrefixStr) extends Sig with EndorsementStatus
