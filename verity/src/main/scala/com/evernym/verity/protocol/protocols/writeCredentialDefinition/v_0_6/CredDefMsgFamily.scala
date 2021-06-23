package com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6

import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import org.json.JSONObject

object CredDefMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "write-cred-def"
  override val version: MsgFamilyVersion =  "0.6"
  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map()

  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "write"                -> classOf[Write],
  )


  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[StatusReport]     -> "status-report",
    classOf[ProblemReport]    -> "problem-report",
    classOf[NeedsEndorsement] -> "needs-endorsement"
  )

}

sealed trait Role
object Role {
  case class Writer() extends Role

  val roles: Set[Role] = Set(Writer())
}

sealed trait Msg extends MsgBase {
  val msgFamily = ""
}

sealed trait SignalMsg extends MsgBase
case class StatusReport(credDefId: String)                          extends SignalMsg
case class ProblemReport(message: String)                           extends SignalMsg
case class NeedsEndorsement(credDefId: String, credDefJson: String) extends SignalMsg

case class RevocationDetails(support_revocation: Boolean, tails_file: String, max_creds: Int) {
  override def toString: MsgName = {
    val json = new JSONObject()
    json.put("support_revocation", support_revocation)
    if (support_revocation) {
      json.put("tails_file", tails_file)
      json.put("max_creds", max_creds)
    }
    json.toString
  }
}

/**
 * Control Messages
 */
trait CredDefControl extends Control
case class InitMsg() extends CredDefControl
case class Write(name: String,
                 schemaId: String,
                 tag: Option[String],
                 revocationDetails: Option[RevocationDetails],
                 endorserDID: Option[String]=None) extends Msg with CredDefControl {
  override def validate(): Unit = {
    checkRequired("name", name)
    checkRequired("schemaId", schemaId)
    checkOptionalNotEmpty("tag", tag)
    checkOptionalNotEmpty("endorserDID", endorserDID)
    endorserDID.foreach{ endorser =>
      checkValidDID("endorserDID", endorser)
    }
  }
}

/**
 * Errors
 */
trait WriteCredDefException   extends Exception {
  def err: String
}
case class SchemaNotFound(id: String)      extends WriteCredDefException {
  def err = s"Schema not found for id: $id"
}
case object MissingIssuerDID               extends WriteCredDefException {
  def err = "Missing DID which can write to the ledger"
}
