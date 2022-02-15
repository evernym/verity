package com.evernym.verity.vdr.base

import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.testkit.MockLedger.NO_ENDORSEMENT
import com.evernym.verity.vdr._


//base implementation for any VDR based mock ledgers (Indy, Cheqd etc)
trait InMemLedger {
  def namespaces: List[Namespace]

  def prepareDidTxn(txnSpecificParams: TxnSpecificParams,
                    submitterDid: VdrDid,
                    endorser: Option[String]): PreparedTxnResult = {
    val id = FQIdentifier(submitterDid, namespaces)
    val schema = TestVDRDid(submitterDid, txnSpecificParams)
    val jsonPayload = JacksonMsgCodec.toJson(schema)
    new PreparedTxnResult(id.vdrNamespace, jsonPayload.getBytes, SIGN_ED25519_SHA512_SINGLE, jsonPayload.getBytes, NO_ENDORSEMENT)
  }

  def prepareSchemaTxn(txnSpecificParams: TxnSpecificParams,
                       fqSchemaId: FQSchemaId,
                       submitterDid: VdrDid,
                       endorser: Option[String]): PreparedTxnResult = {
    val id = FQIdentifier(fqSchemaId, namespaces)
    val schema = TestVDRSchema(submitterDid, fqSchemaId, txnSpecificParams)
    val jsonPayload = JacksonMsgCodec.toJson(schema)
    new PreparedTxnResult(id.vdrNamespace, jsonPayload.getBytes, SIGN_ED25519_SHA512_SINGLE, jsonPayload.getBytes, NO_ENDORSEMENT)
  }

  def prepareCredDefTxn(txnSpecificParams: TxnSpecificParams,
                        fQCredDefId: FQCredDefId,
                        submitterDid: VdrDid,
                        endorser: Option[String]): PreparedTxnResult = {
    val id = FQIdentifier(fQCredDefId, namespaces)
    val credDef = TestVDRCredDef(submitterDid, fQCredDefId, extractSchemaId(txnSpecificParams), txnSpecificParams)
    val jsonPayload = JacksonMsgCodec.toJson(credDef)
    new PreparedTxnResult(id.vdrNamespace, jsonPayload.getBytes, SIGN_ED25519_SHA512_SINGLE, jsonPayload.getBytes, NO_ENDORSEMENT)
  }

  def submitTxn(txnBytes: Array[Byte]): TxnResult = {
    val node = JacksonMsgCodec.docFromStrUnchecked(new String(txnBytes))
    node.get("payloadType").asText() match {
      case "schema" =>
        val s = JacksonMsgCodec.fromJson[TestVDRSchema](new String(txnBytes))
        schemas = schemas + (s.schemaId -> s.json.getBytes)

      case "creddef" =>
        val cd = JacksonMsgCodec.fromJson[TestVDRCredDef](new String(txnBytes))
        credDefs = credDefs + (cd.credDefId -> cd.json.getBytes)

      case other =>
        throw new RuntimeException("payload type not supported: " + other)
    }
    "{}"
  }

  def resolveSchema(fqSchemaId: FQSchemaId): VdrSchema = {
    val data = schemas.getOrElse(fqSchemaId, throw new RuntimeException("schema not found for given id: " +
      fqSchemaId + s" (available schemas: ${schemas.keys.mkString(", ")})"))
    new String(data)
  }

  def resolveCredDef(fqCredDefId: FQCredDefId): VdrCredDef = {
    val data = credDefs.getOrElse(fqCredDefId, throw new RuntimeException("cred def not found for given id: " +
      fqCredDefId + s" (available cred defs: ${credDefs.keys.mkString(", ")})"))
    new String(data)
  }

  def resolveDid(fqDid: FQDid): VdrDid = {
    val data = didDocs.getOrElse(fqDid, throw new RuntimeException("did doc not found for given id: " +
      fqDid + s" (available did docs: ${didDocs.keys.mkString(", ")})"))
    new String(data)
  }

  def addDidDoc(dd: TestVDRDidDoc): Unit = {
    val ddJson = JacksonMsgCodec.toJson(dd)
    didDocs = didDocs + (dd.id -> ddJson.getBytes)
  }

  protected def extractSubmitterDid(txnBytes: Array[Byte]): String = {
    val node = JacksonMsgCodec.docFromStrUnchecked(new String(txnBytes))
    node.get("payloadType").asText() match {
      case "schema" =>
        val s = JacksonMsgCodec.fromJson[TestVDRSchema](new String(txnBytes))
        s.submitterDid

      case "creddef" =>
        val cd = JacksonMsgCodec.fromJson[TestVDRCredDef](new String(txnBytes))
        cd.submitterDid

      case other =>
        throw new RuntimeException("payload type not supported: " + other)
    }
  }

  private def extractSchemaId(json: String): String = {
    val node = JacksonMsgCodec.docFromStrUnchecked(json)
    node.get("schemaId").asText()
  }

  private var schemas: Map[FQSchemaId, Payload] = Map.empty
  private var credDefs: Map[FQCredDefId, Payload] = Map.empty
  private var didDocs: Map[FQDid, Payload] = Map.empty

  type Payload = Array[Byte]
}

trait TestPayloadBase {
  def payloadType: String
}

case class TestVDRDid(submitterDid: FQDid, json: String)
  extends TestPayloadBase {
  override val payloadType: String = "did"
}

case class TestVDRSchema(submitterDid: FQDid, schemaId: FQSchemaId, json: String)
  extends TestPayloadBase {
  override val payloadType: String = "schema"
}

case class TestVDRCredDef(submitterDid: FQDid, credDefId: FQCredDefId, schemaId: FQSchemaId, json: String)
  extends TestPayloadBase {
  override val payloadType: String = "creddef"
}

case class TestVDRDidDoc(id: FQDid, verKey: VerKeyStr, endpoint: Option[String])
  extends TestPayloadBase {
  override val payloadType: String = "diddoc"
}