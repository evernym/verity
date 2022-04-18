package com.evernym.verity.protocol.protocols.writeSchema.v_0_6

import com.evernym.verity.did.DidStr
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.validate.ValidateHelper.{checkOptionalNotEmpty, checkRequired, checkValidDID}

object WriteSchemaMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "write-schema"
  override val version: MsgFamilyVersion = "0.6"
  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
  )

  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "write"                -> classOf[Write],
    "endorsement-result"   -> classOf[EndorsementResult]
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[StatusReport]                 -> "status-report",
    classOf[ProblemReport]                -> "problem-report",
    classOf[NeedsEndorsement]             -> "needs-endorsement"
  )
}

sealed trait Role
object Role {
  case class Writer() extends Role

  val roles: Set[Role] = Set(Writer())
}

sealed trait Msg extends MsgBase{
  val msgFamily = ""
}

sealed trait SignalMsg extends MsgBase
case class StatusReport(schemaId: String)                         extends SignalMsg
case class ProblemReport(message: String)                         extends SignalMsg
case class NeedsEndorsement(schemaId: String, schemaJson: String) extends SignalMsg

/**
 * Control Messages
 */
trait SchemaControl extends Control with MsgBase
case class Write(name: String, version: String, attrNames: Seq[String], endorserDID: Option[DidStr]=None) extends Msg with SchemaControl {
  override def validate(): Unit = {
    checkRequired("name", name)
    checkRequired("version", version)
    checkRequired("attrNames", attrNames)
    checkOptionalNotEmpty("endorserDID", endorserDID)
    endorserDID.foreach{ endorser =>
      checkValidDID("endorserDID", endorser)
    }
  }
}

case class EndorsementResult(code: String, description: String) extends Msg with SchemaControl

/**
 * Errors
 */
trait WriteSchemaException   extends Exception {
  def err: String
}
case object MissingIssuerDID               extends WriteSchemaException {
  def err = "Missing DID which can write to the ledger"
}
