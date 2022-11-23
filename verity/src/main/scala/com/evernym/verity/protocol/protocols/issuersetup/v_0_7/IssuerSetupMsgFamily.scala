package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.validate.ValidateHelper.{checkDIDMethodMatchesLedgerPrefix, checkOptionalNotEmpty, checkRequired, checkValidDID}
import com.evernym.verity.protocol.protocols.{ledgerPrefixStr, ledgerRequestStr}
import com.evernym.verity.vdr.LedgerPrefix

object IssuerSetupMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "issuer-setup"
  override val version: MsgFamilyVersion = "0.7"

  override protected val controlMsgs: Map[MsgName, Class[_]] = Map (
    "initialize"                       -> classOf[Initialize],
    "create"                           -> classOf[Create],
    "current-public-identifier"        -> classOf[CurrentPublicIdentifier],
    "endorsement-result"               -> classOf[EndorsementResult],
    "current-issuer-identifier-result" -> classOf[CurrentIssuerIdentifierResult]
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map (
    classOf[ProblemReport]            -> "problem-report",
    classOf[PublicIdentifierCreated]  -> "public-identifier-created",
    classOf[PublicIdentifier]         -> "public-identifier",
    classOf[GetIssuerIdentifier]      -> "get-issuer-identifier"
  )

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map.empty
}

sealed trait Msg extends MsgBase


trait IssuerSetupControl extends Control with MsgBase

case class Initialize(params: Parameters) extends Msg with IssuerSetupControl {
  def parametersStored: Seq[InitParam] = params.initParams.map(p => InitParam(p.name, p.value)).toSeq
}

case class Create(ledgerPrefix: LedgerPrefix, endorser: Option[String]) extends Msg with IssuerSetupControl {
  override def validate(): Unit = {
    checkRequired("ledgerPrefix", ledgerPrefix)
    checkOptionalNotEmpty("endorserDID", endorser)
    endorser.foreach{ endorser =>
      checkValidDID("endorserDID", endorser)
      checkDIDMethodMatchesLedgerPrefix("endorserDID", endorser, ledgerPrefix)
    }
  }
}
case class CurrentPublicIdentifier() extends IssuerSetupControl
case class EndorsementResult(code: String, description: String) extends IssuerSetupControl
case class CurrentIssuerIdentifierResult(create: Create, identifier: Option[PublicIdentifier]) extends IssuerSetupControl

sealed trait Sig extends Msg
case class PublicIdentifier(did: DidStr, verKey: VerKeyStr) extends Sig
case class PublicIdentifierCreated(identifier: PublicIdentifier, status: EndorsementStatus) extends Sig
case class ProblemReport(message: String) extends Sig
case class GetIssuerIdentifier(create: Create) extends Sig
sealed trait EndorsementStatus
case class NeedsEndorsement(needsEndorsement: ledgerRequestStr) extends Sig with EndorsementStatus
case class WrittenToLedger(writtenToLedger: ledgerPrefixStr) extends Sig with EndorsementStatus