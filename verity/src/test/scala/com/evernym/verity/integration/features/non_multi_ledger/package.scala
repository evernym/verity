package com.evernym.verity.integration.features

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.protocol.protocols.ProtocolHelpers.{CRED_DEF_ID, ISSUER_DID, SCHEMA_ID}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.Sent
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.util.Base64Util
import com.evernym.verity.vdr.VDRUtil
import org.json.JSONObject

import scala.util.Try


package object non_multi_ledger {

  val UNQUALIFIED_LEDGER_PREFIX = "did:indy:sovrin"

  def checkOfferSentForNonFQIdentifiers(msg: Sent): Unit = {
    checkOfferSentForIdentifiers(msg, expectsFQIdentifiers = false)
  }

  def checkOfferSentForFQIdentifiers(msg: Sent): Unit = {
    checkOfferSentForIdentifiers(msg, expectsFQIdentifiers = true)
  }

  def checkCredSentForNonFQIdentifiers(msg: Sent): Unit = {
    checkCredSentForIdentifiers(msg, expectsFQIdentifiers = false)
  }

  def checkCredSentForFQIdentifiers(msg: Sent): Unit = {
    checkCredSentForIdentifiers(msg, expectsFQIdentifiers = true)
  }

  def checkPresentationForNonFQIdentifiers(msg: PresentationResult): Unit = {
    checkPresentationForIdentifiers(msg, expectsFQIdentifiers = false)
  }

  def checkPresentationForFQIdentifiers(msg: PresentationResult): Unit = {
    checkPresentationForIdentifiers(msg, expectsFQIdentifiers = true)
  }

  def checkOfferSentForIdentifiers(sent: Sent, expectsFQIdentifiers: Boolean): Unit = {
    val sentOfferMsg = sent.msg.asInstanceOf[Map[String, Any]]
    val attachedDataBase64 = sentOfferMsg("offers~attach").asInstanceOf[List[Map[String, Any]]].map(v => v("data").asInstanceOf[Map[String,String]]("base64"))
    attachedDataBase64.foreach { base64Data =>
      val base64Decoded = Base64Util.decodeToStr(base64Data)
      checkForIdentifiers(base64Decoded, expectsFQIdentifiers)
    }
  }

  def checkCredSentForIdentifiers(sent: Sent, expectsFQIdentifiers: Boolean): Unit = {
    val sentCredMsg = sent.msg.asInstanceOf[Map[String, Any]]
    val attachedDataBase64 = sentCredMsg("credentials~attach").asInstanceOf[List[Map[String, Any]]].map(v => v("data").asInstanceOf[Map[String, String]]("base64"))
    attachedDataBase64.foreach { base64Data =>
      val base64Decoded = Base64Util.decodeToStr(base64Data)
      checkForIdentifiers(base64Decoded, expectsFQIdentifiers)
    }
  }

  def checkPresentationForIdentifiers(presentation: PresentationResult, expectsFQIdentifiers: Boolean): Unit = {
    presentation.requested_presentation.identifiers.foreach { identifier =>
      val jsonString = DefaultMsgCodec.toJson(identifier)
      checkForIdentifiers(jsonString, expectsFQIdentifiers)
    }
  }

  def checkForIdentifiers(jsonString: String, expectsFQIdentifiers: Boolean): Unit = {
    List(ISSUER_DID, SCHEMA_ID, CRED_DEF_ID).foreach { fieldName =>
      extractField(jsonString, fieldName).foreach { fieldValue =>
        checkIdentifier(fieldName, fieldValue, expectsFQIdentifiers)
      }
    }
  }

  def checkIdentifier(fieldName: String,
                      fieldValue: String,
                      expectsFQIdentifiers: Boolean): Unit = {
    fieldName match {
      case ISSUER_DID =>
        val expectedFieldValue =
          if (expectsFQIdentifiers) VDRUtil.toFqDID(fieldValue, vdrMultiLedgerSupportEnabled = true, UNQUALIFIED_LEDGER_PREFIX, Map.empty)
          else VDRUtil.toLegacyNonFqDid(fieldValue, vdrMultiLedgerSupportEnabled = false)
        checkFieldValue(fieldName, fieldValue, expectedFieldValue)
      case SCHEMA_ID =>
        val expectedFieldValue =
          if (expectsFQIdentifiers) VDRUtil.toFqSchemaId_v0(fieldValue, None, Option(UNQUALIFIED_LEDGER_PREFIX), vdrMultiLedgerSupportEnabled = true)
          else VDRUtil.toLegacyNonFqSchemaId(fieldValue, vdrMultiLedgerSupportEnabled = false)
        checkFieldValue(fieldName, fieldValue, expectedFieldValue)
      case CRED_DEF_ID =>
        val expectedFieldValue =
          if (expectsFQIdentifiers) VDRUtil.toFqCredDefId_v0(fieldValue, None, Option(UNQUALIFIED_LEDGER_PREFIX), vdrMultiLedgerSupportEnabled = true)
          else VDRUtil.toLegacyNonFqCredDefId(fieldValue, vdrMultiLedgerSupportEnabled = false)
        checkFieldValue(fieldName, fieldValue, expectedFieldValue)
    }
  }

  private def checkFieldValue(fieldName: String,
                              fieldValue: String,
                              expectedFieldValue: String): Unit = {
    if (fieldValue != expectedFieldValue) throw new RuntimeException(s"'$fieldName' value '$fieldValue' was not as expected '$expectedFieldValue'")
  }

  private def extractField(jsonString: String,
                           fieldName: String): Option[String] = {
    val jsonObject = new JSONObject(jsonString)
    Try(jsonObject.getString(fieldName)).toOption
  }
}
