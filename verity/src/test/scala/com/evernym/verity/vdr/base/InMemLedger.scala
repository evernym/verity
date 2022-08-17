package com.evernym.verity.vdr.base

import com.evernym.vdrtools.vdr.VdrResults.PreparedTxnResult
import com.evernym.verity.actor.agent.{AttrName, AttrValue}
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.did.{DidPair, DidStr, VerKeyStr}
import com.evernym.verity.ledger.{GetAttribResp, Submitter}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.testkit.MockLedger.INDY_ENDORSEMENT
import com.evernym.verity.util2.Status.{DATA_NOT_FOUND, StatusDetailException}
import com.evernym.verity.vdr._
import com.evernym.verity.vdr.base.PayloadConstants._
import com.evernym.verity.vdr.service.VDRAdapterUtil
import com.evernym.verity.vdr.service.VDRAdapterUtil.SCHEMA_ID
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
    node.get(TYPE).asText() match {

      case SCHEMA =>
        val s = JacksonMsgCodec.fromJson[MockVdrSchema](new String(txnBytes))
        val schemaJson = new JSONObject(s.json)
        schemaJson.put("seqNo", 10)
        schemas = schemas + (unqualifiedSchemaId(s.schemaId) -> schemaJson.toString.getBytes)

      case CRED_DEF =>
        val cd = JacksonMsgCodec.fromJson[MockVdrCredDef](new String(txnBytes))
        credDefs = credDefs + (unqualifiedCredDefId(cd.credDefId) -> cd.json.getBytes)

      case other =>
        throw new RuntimeException("payload type not supported: " + other)
    }
    "{}"
  }

  def resolveSchema(fqSchemaId: FqSchemaId): VdrSchema = {
    val data = schemas.getOrElse(unqualifiedSchemaId(fqSchemaId),
      throw new RuntimeException("schema not found for given id: " + fqSchemaId))
    new String(data)
  }

  def resolveCredDef(fqCredDefId: FqCredDefId): VdrCredDef = {
    val data = credDefs.getOrElse(unqualifiedCredDefId(fqCredDefId),
      throw new RuntimeException("cred def not found for given id: " + fqCredDefId))
    new String(data)
  }

  def resolveDid(fqDid: FqDID): VdrDid = {
    val data = didDocs.getOrElse(unqualifiedDid(fqDid),
      throw new RuntimeException("did doc not found for given id: " +
      fqDid + s" (available did docs: ${didDocs.keys.mkString(", ")})"))
    new String(data)
  }

  def addDidDoc(dd: MockVdrDIDDoc): Unit = {
    val ddJson = JacksonMsgCodec.toJson(dd)
    didDocs = didDocs + (dd.id -> ddJson.getBytes)
  }

  private def unqualifiedDid(didStr: DidStr): DidStr = {
    VDRUtil.toLegacyNonFqDid(didStr, vdrMultiLedgerSupportEnabled = false)
  }
  private def unqualifiedSchemaId(schemaId: SchemaId): SchemaId = {
    VDRUtil.toLegacyNonFqSchemaId(schemaId, vdrMultiLedgerSupportEnabled = false)
  }

  private def unqualifiedCredDefId(credDefId: CredDefId): CredDefId = {
    VDRUtil.toLegacyNonFqCredDefId(credDefId, vdrMultiLedgerSupportEnabled = false)
  }

  private def extractSchemaId(json: String): String = {
    val node = JacksonMsgCodec.docFromStrUnchecked(json)
    node.get(SCHEMA_ID).asText()
  }

  private def extractNamespace(id: String): Namespace =
    Try(VDRUtil.extractNamespace(Option(id), None)).getOrElse(INDY_SOVRIN_NAMESPACE)

  private var schemas: Map[SchemaId, Payload] = Map.empty
  private var credDefs: Map[CredDefId, Payload] = Map.empty
  private var didDocs: Map[DidStr, Payload] = Map.empty

  type Payload = Array[Byte]

  //============================================= WORKAROUND =======================================================
  //NOTE: this is workaround until vdr apis starts supporting updating did docs

  var attribs: Map[DidStr, Map[AttrName, AttrValue]] = Map.empty

  def addNym(submitter: Submitter, didPair: DidPair): Unit = {
    didDocs += didPair.did -> s"""{"${VDRAdapterUtil.DID}":"${didPair.did}", "${VDRAdapterUtil.VER_KEY}":"${didPair.verKey}"}""".getBytes
  }

  def addAttrib(submitter: Submitter, did: DidStr, attrName: AttrName, attrValue: AttrValue): Unit = {
    val oldDidAttribs = attribs.getOrElse(did, Map.empty)
    val newDidAttribs = oldDidAttribs ++ Map(attrName -> attrValue)
    attribs += did -> newDidAttribs
  }

  def getAttrib(submitter: Submitter, did: DidStr, attrName: AttrName): GetAttribResp = {
    attribs.getOrElse(did, Map.empty).get(attrName) match {
      case Some(attrValue) =>
        GetAttribResp(
          MockLedgerTxnExecutor.buildTxnResp(
            did,
            Some(did),
            Some(Map(attrName -> attrValue)),
            "104"
          )
        )
      case None => throw StatusDetailException(DATA_NOT_FOUND)
    }
  }

  def getSchema(schemaId: SchemaId): VdrSchema = {
    val data = schemas.getOrElse(schemaId,
      throw new RuntimeException("schema not found for given id: " + schemaId))
    new String(data)
  }

  def getCredDef(credDefId: CredDefId): VdrCredDef = {
    val data = credDefs.getOrElse(credDefId,
      throw new RuntimeException("cred def not found for given id: " + credDefId))
    new String(data)
  }
}


object PayloadConstants {
  val TYPE = "payloadType"
  val DID = "did"
  val SCHEMA = "schema"
  val CRED_DEF = "creddef"
  val DID_DOC = "diddoc"
}

trait MockPayloadBase {
  def payloadType: String
}

case class MockVdrDID(submitterDid: FqDID, json: String)
  extends MockPayloadBase {
  override val payloadType: String = DID
}

case class MockVdrSchema(submitterDid: FqDID, schemaId: FqSchemaId, json: String)
  extends MockPayloadBase {
  override val payloadType: String = SCHEMA
}

case class MockVdrCredDef(submitterDid: FqDID, credDefId: FqCredDefId, schemaId: FqSchemaId, json: String)
  extends MockPayloadBase {
  override val payloadType: String = CRED_DEF
}

case class MockVdrDIDDoc(id: FqDID, verKey: VerKeyStr, endpoint: Option[String])
  extends MockPayloadBase {
  override val payloadType: String = DID_DOC
}