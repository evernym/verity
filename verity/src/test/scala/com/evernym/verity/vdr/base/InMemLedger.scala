package com.evernym.verity.vdr.base

import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.VerKeyStr
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.testkit.MockLedger.INDY_ENDORSEMENT
import com.evernym.verity.vdr.VDRUtil.extractNamespace
import com.evernym.verity.vdr._
import org.json.JSONObject


//base implementation for any VDR based mock ledgers (Indy, Cheqd etc)
trait InMemLedger {

  def allSupportedNamespaces: List[Namespace]

  def prepareDidTxn(txnSpecificParams: TxnSpecificParams,
                    submitterDid: VdrDid,
                    endorser: Option[String]): PreparedTxnResult = {
    val namespace = extractNamespace(Option(submitterDid), None)
    val schema = TestVDRDid(submitterDid, txnSpecificParams)
    val jsonPayload = JacksonMsgCodec.toJson(schema)
    new PreparedTxnResult(namespace, jsonPayload.getBytes, SIGN_ED25519_SHA512_SINGLE, jsonPayload.getBytes, INDY_ENDORSEMENT)
  }

  def prepareSchemaTxn(txnSpecificParams: TxnSpecificParams,
                       fqSchemaId: FqSchemaId,
                       submitterDid: VdrDid,
                       endorser: Option[String]): PreparedTxnResult = {
    val namespace = extractNamespace(Option(submitterDid), None)
    val schema = TestVDRSchema(submitterDid, fqSchemaId, txnSpecificParams)
    val jsonPayload = JacksonMsgCodec.toJson(schema)
    new PreparedTxnResult(namespace, jsonPayload.getBytes, SIGN_ED25519_SHA512_SINGLE, jsonPayload.getBytes, INDY_ENDORSEMENT)
  }

  def prepareCredDefTxn(txnSpecificParams: TxnSpecificParams,
                        fQCredDefId: FqCredDefId,
                        submitterDid: VdrDid,
                        endorser: Option[String]): PreparedTxnResult = {
    val namespace = extractNamespace(Option(submitterDid), None)
    val credDef = TestVDRCredDef(submitterDid, fQCredDefId, extractSchemaId(txnSpecificParams), txnSpecificParams)
    val jsonPayload = JacksonMsgCodec.toJson(credDef)
    new PreparedTxnResult(namespace, jsonPayload.getBytes, SIGN_ED25519_SHA512_SINGLE, jsonPayload.getBytes, INDY_ENDORSEMENT)
  }

  def submitTxn(txnBytes: Array[Byte]): TxnResult = {
    val node = JacksonMsgCodec.docFromStrUnchecked(new String(txnBytes))
    node.get("payloadType").asText() match {
      case "schema" =>
        val s = JacksonMsgCodec.fromJson[TestVDRSchema](new String(txnBytes))
        val schemaJson = new JSONObject(s.json)
        schemaJson.put("seqNo", 10)
        schemas = schemas + (s.schemaId -> schemaJson.toString.getBytes)

      case "creddef" =>
        val cd = JacksonMsgCodec.fromJson[TestVDRCredDef](new String(txnBytes))
        credDefs = credDefs + (cd.credDefId -> cd.json.getBytes)

      case other =>
        throw new RuntimeException("payload type not supported: " + other)
    }
    "{}"
  }

  def resolveSchema(fqSchemaId: FqSchemaId): VdrSchema = {
    val data = schemas.getOrElse(VDRUtil.toLegacyNonFqSchemaId(fqSchemaId), throw new RuntimeException("schema not found for given id: " + fqSchemaId))
    new String(data)
  }

  def resolveCredDef(fqCredDefId: FqCredDefId): VdrCredDef = {
    val data = credDefs.getOrElse(VDRUtil.toLegacyNonFqCredDefId(fqCredDefId), throw new RuntimeException("cred def not found for given id: " + fqCredDefId))
    new String(data)
  }

  def resolveDid(fqDid: FqDID): VdrDid = {
    val data = didDocs.getOrElse(fqDid, throw new RuntimeException("did doc not found for given id: " +
      fqDid + s" (available did docs: ${didDocs.keys.mkString(", ")})"))
    new String(data)
  }

  def addDidDoc(dd: TestVDRDidDoc): Unit = {
    val ddJson = JacksonMsgCodec.toJson(dd)
    didDocs = didDocs + (dd.id -> ddJson.getBytes)
  }

  private def extractSchemaId(json: String): String = {
    val node = JacksonMsgCodec.docFromStrUnchecked(json)
    node.get("schemaId").asText()
  }

//  def checkSchemaId(schemaId: String): Unit = {
//    if (! schemaId.startsWith("did:indy:"))
//      throw new RuntimeException(s"non fully qualified schema id: $schemaId")
//  }
//
//  def checkCredDefId(credDefId: String): Unit = {
//    if (! credDefId.startsWith("did:"))
//      throw new RuntimeException(s"non fully qualified cred def id: $credDefId")
//  }

  //  def isFqSchemaId(schemaId: String, issuerDid: FqDID): Unit = {
  //    if (VDRUtil.toFqSchemaId_v0(schemaId, Option(issuerDid), None) != schemaId)
  //      throw new RuntimeException(s"non fully qualified schema id: $schemaId")
  //  }
  //
  //  def isFqCredDefId(credDefId: String, issuerDid: FqDID): Unit = {
  //    if (VDRUtil.toFqCredDefId_v0(credDefId, Option(issuerDid), None) != credDefId)
  //      throw new RuntimeException(s"non fully qualified cred def id: $credDefId")
  //  }


  private var schemas: Map[FqSchemaId, Payload] = Map.empty
  private var credDefs: Map[FqCredDefId, Payload] = Map.empty
  private var didDocs: Map[FqDID, Payload] = Map.empty

  type Payload = Array[Byte]
}

trait TestPayloadBase {
  def payloadType: String
}

case class TestVDRDid(submitterDid: FqDID, json: String)
  extends TestPayloadBase {
  override val payloadType: String = "did"
}

case class TestVDRSchema(submitterDid: FqDID, schemaId: FqSchemaId, json: String)
  extends TestPayloadBase {
  override val payloadType: String = "schema"
}

case class TestVDRCredDef(submitterDid: FqDID, credDefId: FqCredDefId, schemaId: FqSchemaId, json: String)
  extends TestPayloadBase {
  override val payloadType: String = "creddef"
}

case class TestVDRDidDoc(id: FqDID, verKey: VerKeyStr, endpoint: Option[String])
  extends TestPayloadBase {
  override val payloadType: String = "diddoc"
}