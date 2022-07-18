package com.evernym.verity.vdr.base

import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.testkit.MockLedger.INDY_ENDORSEMENT
import com.evernym.verity.vdr._
import org.json.JSONObject

import scala.util.Try


//base implementation for a mock ledger (Indy, Cheqd etc)
trait InMemLedger {

  def prepareDidTxn(txnSpecificParams: TxnSpecificParams,
                    submitterDid: VdrDid,
                    endorser: Option[String]): PreparedTxnResult = {
    val schema = MockVdrDID(submitterDid, txnSpecificParams)
    val jsonPayload = JacksonMsgCodec.toJson(schema)
    new PreparedTxnResult(extractNamespace(submitterDid), jsonPayload.getBytes,
      SIGN_ED25519_SHA512_SINGLE, jsonPayload.getBytes, INDY_ENDORSEMENT)
  }

  def prepareSchemaTxn(txnSpecificParams: TxnSpecificParams,
                       schemaId: SchemaId,
                       submitterDid: VdrDid,
                       endorser: Option[String]): PreparedTxnResult = {
    val schema = MockVdrSchema(submitterDid, schemaId, txnSpecificParams)
    val jsonPayload = JacksonMsgCodec.toJson(schema)
    new PreparedTxnResult(extractNamespace(submitterDid), jsonPayload.getBytes,
      SIGN_ED25519_SHA512_SINGLE, jsonPayload.getBytes, INDY_ENDORSEMENT)
  }

  def prepareCredDefTxn(txnSpecificParams: TxnSpecificParams,
                        credDefId: CredDefId,
                        submitterDid: VdrDid,
                        endorser: Option[String]): PreparedTxnResult = {
    val credDef = MockVdrCredDef(submitterDid, credDefId, extractSchemaId(txnSpecificParams), txnSpecificParams)
    val jsonPayload = JacksonMsgCodec.toJson(credDef)
    new PreparedTxnResult(extractNamespace(submitterDid), jsonPayload.getBytes,
      SIGN_ED25519_SHA512_SINGLE, jsonPayload.getBytes, INDY_ENDORSEMENT)
  }

  def submitTxn(txnBytes: Array[Byte]): TxnResult = {
    val node = JacksonMsgCodec.docFromStrUnchecked(new String(txnBytes))
    node.get("payloadType").asText() match {
      case "schema" =>
        val s = JacksonMsgCodec.fromJson[MockVdrSchema](new String(txnBytes))
        val schemaJson = new JSONObject(s.json)
        schemaJson.put("seqNo", 10)
        schemas = schemas + (s.schemaId -> schemaJson.toString.getBytes)

      case "creddef" =>
        val cd = JacksonMsgCodec.fromJson[MockVdrCredDef](new String(txnBytes))
        credDefs = credDefs + (cd.credDefId -> cd.json.getBytes)

      case other =>
        throw new RuntimeException("payload type not supported: " + other)
    }
    "{}"
  }

  def resolveSchema(fqSchemaId: FqSchemaId): VdrSchema = {
    val data = schemas.getOrElse(fqSchemaId,
      throw new RuntimeException("schema not found for given id: " + fqSchemaId))
    new String(data)
  }

  def resolveCredDef(fqCredDefId: FqCredDefId): VdrCredDef = {
    val data = credDefs.getOrElse(fqCredDefId,
      throw new RuntimeException("cred def not found for given id: " + fqCredDefId))
    new String(data)
  }

  def resolveDid(fqDid: FqDID): VdrDid = {
    val data = didDocs.getOrElse(fqDid, throw new RuntimeException("did doc not found for given id: " +
      fqDid + s" (available did docs: ${didDocs.keys.mkString(", ")})"))
    new String(data)
  }

  def addDidDoc(dd: MockVdrDIDDoc): Unit = {
    val ddJson = JacksonMsgCodec.toJson(dd)
    didDocs = didDocs + (dd.id -> ddJson.getBytes)
  }

  private def extractSchemaId(json: String): String = {
    val node = JacksonMsgCodec.docFromStrUnchecked(json)
    node.get("schemaId").asText()
  }

  private def extractNamespace(id: String): Namespace =
    Try(VDRUtil.extractNamespace(Option(id), None)).getOrElse(INDY_SOVRIN_NAMESPACE)

  private var schemas: Map[SchemaId, Payload] = Map.empty
  private var credDefs: Map[CredDefId, Payload] = Map.empty
  private var didDocs: Map[DidStr, Payload] = Map.empty

  type Payload = Array[Byte]
}

trait MockPayloadBase {
  def payloadType: String
}

case class MockVdrDID(submitterDid: FqDID, json: String)
  extends MockPayloadBase {
  override val payloadType: String = "did"
}

case class MockVdrSchema(submitterDid: FqDID, schemaId: FqSchemaId, json: String)
  extends MockPayloadBase {
  override val payloadType: String = "schema"
}

case class MockVdrCredDef(submitterDid: FqDID, credDefId: FqCredDefId, schemaId: FqSchemaId, json: String)
  extends MockPayloadBase {
  override val payloadType: String = "creddef"
}

case class MockVdrDIDDoc(id: FqDID, verKey: VerKeyStr, endpoint: Option[String])
  extends MockPayloadBase {
  override val payloadType: String = "diddoc"
}