package com.evernym.verity.protocol.testkit

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.{ActorSpec, TestAppConfig}
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.did.DidStr
import com.evernym.verity.ledger._
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.ledger.{LedgerAccess, LedgerAccessException, LedgerRejectException}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.testkit.MockLedger.TEST_INDY_SOVRIN_NAMESPACE
import com.evernym.verity.protocol.testkit.MockableLedgerAccess.MOCK_NOT_ENDORSER
import com.evernym.verity.testkit.{BasicSpecBase, TestWallet}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.{ExecutionContextProvider, Status}
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vdr._

import com.evernym.verity.vdr.base.INDY_SOVRIN_NAMESPACE
import org.json.JSONObject

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Try}


object MockableLedgerAccess {
  val MOCK_NO_DID = "7Pt7EfStLXeYNSmpJJSktm"
  val MOCK_NOT_ENDORSER = "GnJ79a5XAuTaxHXWSLRyqP"
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp

  def apply(): MockableLedgerAccess = {
    new MockableLedgerAccess(ecp.futureExecutionContext)
  }

  def apply(ledgerAvailable: Boolean): MockableLedgerAccess =
    new MockableLedgerAccess(ecp.futureExecutionContext, ledgerAvailable = ledgerAvailable)
}

class MockableLedgerAccess(executionContext: ExecutionContext,
                           val schemas: Map[String, Schema] = MockLedgerData.schemas01,
                           val credDefs: Map[String, CredDef] = MockLedgerData.credDefs01,
                           val ledgerAvailable: Boolean = true)
  extends LedgerAccess with MockAsyncOpRunner with ActorSpec with BasicSpecBase{

  import MockableLedgerAccess._

  implicit def asyncAPIContext: AsyncAPIContext = AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)

  val testWallet = new TestWallet(executionContext, false, system)
  implicit val wap: WalletAPIParam = testWallet.wap

  lazy val invalidEndorserError: String = "Rule for this action is: 1 TRUSTEE signature is required OR 1 STEWARD " +
    "signature is required OR 1 ENDORSER signature is required OR 1 signature of any role is required with additional" +
    " metadata fees schema\\nFailed checks:\\nConstraint: 1 TRUSTEE signature is required, Error: Not enough TRUSTEE " +
    "signatures\\nConstraint: 1 STEWARD signature is required, Error: Not enough STEWARD signatures\\nConstraint: " +
    "1 ENDORSER signature is required, Error: Not enough ENDORSER signatures\\nConstraint: 1 signature of any role " +
    "is required with additional metadata fees schema, Error: Fees are required for this txn type"

  override def prepareSchemaTxn(schemaJson: String,
                                schemaId: SchemaId,
                                submitterDID: FqDID,
                                endorser: Option[String])
                               (handler: Try[PreparedTxn] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        val jsonObject = new JSONObject(schemaJson)
        endorser.foreach(eid => jsonObject.put("endorser", eid))
        val json = jsonObject.toString()
        submitterDids += json.hashCode -> submitterDID
        Try(PreparedTxn(TEST_INDY_SOVRIN_NAMESPACE, SIGN_ED25519_SHA512_SINGLE, json.getBytes, Array.empty, INDY_ENDORSEMENT))
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def prepareCredDefTxn(credDefJson: String,
                                 credDefId: CredDefId,
                                 submitterDID: FqDID,
                                 endorser: Option[String])
                                (handler: Try[PreparedTxn] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        val jsonObject = new JSONObject(credDefJson)
        endorser.foreach(eid => jsonObject.put("endorser", eid))
        val json = jsonObject.toString()
        submitterDids += json.hashCode -> submitterDID
        Try(PreparedTxn(TEST_INDY_SOVRIN_NAMESPACE, SIGN_ED25519_SHA512_SINGLE, json.getBytes, Array.empty, INDY_ENDORSEMENT))
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def prepareDidTxn(didJson: String,
                             submitterDID: FqDID,
                             endorser: Option[String])
                            (handler: Try[PreparedTxn] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        val jsonObject = new JSONObject(didJson)
        endorser.foreach(eid => jsonObject.put("endorser", eid))
        val json = jsonObject.toString()
        submitterDids += json.hashCode -> submitterDID
        Try(PreparedTxn(TEST_INDY_SOVRIN_NAMESPACE, SIGN_ED25519_SHA512_SINGLE, json.getBytes, Array.empty, INDY_ENDORSEMENT))
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte])
                        (handler: Try[SubmittedTxn] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        val submitterDID = extractSubmitterDID(preparedTxn)
        if (submitterDID.equals(fqDID(MOCK_NO_DID, force = false))) Failure(LedgerRejectException(s"verkey for $MOCK_NO_DID cannot be found"))
        else if (submitterDID.equals(fqDID(MOCK_NOT_ENDORSER, force = false))) Failure(LedgerRejectException(invalidEndorserError))
        else Try(SubmittedTxn("{}"))
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveSchema(fqSchemaId: FqSchemaId)(handler: Try[Schema] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        Try{
          val schemaResp = schemas.getOrElse(VDRUtil.toLegacyNonFqSchemaId(fqSchemaId,
            vdrMultiLedgerSupportEnabled), throw new Exception("Unknown schema"))
          Schema(fqSchemaId, schemaResp.json)
        }
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveSchemas(fqSchemaIds: Set[FqSchemaId])(handler: Try[Seq[Schema]] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        Try{
          schemas.filter{ case (id, schema) => fqSchemaIds.map(VDRUtil.toLegacyNonFqSchemaId(_,
            vdrMultiLedgerSupportEnabled)).contains(id)}.values.toSeq
        }
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveCredDef(fqCredDefId: FqCredDefId)(handler: Try[CredDef] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        Try{
          val credDefResp = credDefs.getOrElse(VDRUtil.toLegacyNonFqCredDefId(fqCredDefId,
            vdrMultiLedgerSupportEnabled), throw new Exception("Unknown cred def"))
          CredDef(fqCredDefId, credDefResp.fqSchemaId, credDefResp.json)
        }
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveCredDefs(fqCredDefIds: Set[FqCredDefId])(handler: Try[Seq[CredDef]] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        Try{
          credDefs.filter{ case (id, _) => fqCredDefIds.map(VDRUtil.toLegacyNonFqCredDefId(_,
            vdrMultiLedgerSupportEnabled)).contains(id)}.values.toSeq
        }
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def fqDID(did: DidStr, force: Boolean): FqDID =
    MockLedger.fqID(did, vdrMultiLedgerSupportEnabled)

  override def fqSchemaId(schemaId: SchemaId, issuerDid: Option[FqDID], force: Boolean): FqSchemaId =
    MockLedger.fqSchemaID(schemaId, issuerDid, force || vdrMultiLedgerSupportEnabled)

  override def fqCredDefId(credDefId: CredDefId, issuerDid: Option[FqDID], force: Boolean): FqCredDefId =
    MockLedger.fqCredDefId(credDefId, issuerDid, force || vdrMultiLedgerSupportEnabled)

  def toLegacyNonFqId(did: DidStr): DidStr = {
    MockLedger.toLegacyNonFqId(did, vdrMultiLedgerSupportEnabled)
  }

  override def toLegacyNonFqSchemaId(schemaId: FqSchemaId): SchemaId =
    MockLedger.toLegacyNonFqSchemaId(schemaId, vdrMultiLedgerSupportEnabled)

  override def toLegacyNonFqCredDefId(credDefId: FqCredDefId): CredDefId =
    MockLedger.toLegacyNonFqCredDefId(credDefId, vdrMultiLedgerSupportEnabled)

  override def vdrUnqualifiedLedgerPrefix(): VdrDid = "did:indy:sovrin"

  private def extractSubmitterDID(preparedTxn: PreparedTxn): String = {
    val json = new String(preparedTxn.txnBytes)
    submitterDids(json.hashCode)
  }

  var submitterDids: Map[TxnHash, DidStr] = Map.empty

  val INDY_ENDORSEMENT = s"""{"endorserDid":"$MOCK_NOT_ENDORSER", "type": "Indy"}"""

  val vdrMultiLedgerSupportEnabled: Boolean = false

  override val mockExecutionContext: ExecutionContext = executionContext

  def executionContextProvider: ExecutionContextProvider = ecp

  type TxnHash = Int

}


object MockLedgerData {
  val txnResp = MockLedgerTxnExecutor.buildTxnResp("5XwZzMweuePeFZzArqvepR", None, None, "107")

  val schemas01 = Map(
    "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0" ->
      Schema(
        "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
        DefaultMsgCodec.toJson(
          SchemaV1(
            "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
            "schema-name",
            "0.1",
            Seq("attr-1", "attr2"),
            Some(55),
            "0.1"
          )
        )
      )

  )

  val credDefs01 = Map(
    "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1" ->
      CredDef(
        "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1",
        "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
        DefaultMsgCodec.toJson(
          CredDefV1(
            "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1",
            "CL",
            "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
            "tag",
            "1.0",
            Map.empty
          )
        )
      )
  )


}

object MockLedger {

  val TEST_INDY_SOVRIN_NAMESPACE = INDY_SOVRIN_NAMESPACE
  val TEST_INDY_LEDGER_PREFIX = s"$DID_PREFIX:$TEST_INDY_SOVRIN_NAMESPACE"
  val ledgerPrefixMappings = Map ("did:sov" -> "did:indy:sovrin")

  val INDY_ENDORSEMENT = s"""{"endorserDid":"$MOCK_NOT_ENDORSER", "type": "Indy"}"""

  def fqID(id: String, vdrMultiLedgerSupportEnabled: Boolean): String = {
    VDRUtil.toFqDID(id, vdrMultiLedgerSupportEnabled, TEST_INDY_LEDGER_PREFIX, ledgerPrefixMappings)
  }

  def fqSchemaID(id: String, issuerDid: Option[DidStr], vdrMultiLedgerSupportEnabled: Boolean): String = {
    VDRUtil.toFqSchemaId_v0(id, issuerDid, Option(TEST_INDY_LEDGER_PREFIX), vdrMultiLedgerSupportEnabled)
  }

  def fqCredDefId(id: String, issuerDid: Option[DidStr], vdrMultiLedgerSupportEnabled: Boolean): String = {
    VDRUtil.toFqCredDefId_v0(id, issuerDid, Option(TEST_INDY_LEDGER_PREFIX), vdrMultiLedgerSupportEnabled)
  }

  def toLegacyNonFqId(did: DidStr, vdrMultiLedgerSupportEnabled: Boolean): SchemaId = {
    VDRUtil.toLegacyNonFqDid(did, vdrMultiLedgerSupportEnabled)
  }

  def toLegacyNonFqSchemaId(schemaId: FqSchemaId, vdrMultiLedgerSupportEnabled: Boolean): SchemaId = {
    VDRUtil.toLegacyNonFqSchemaId(schemaId, vdrMultiLedgerSupportEnabled)
  }

  def toLegacyNonFqCredDefId(credDefId: FqCredDefId, vdrMultiLedgerSupportEnabled: Boolean): CredDefId = {
    VDRUtil.toLegacyNonFqCredDefId(credDefId, vdrMultiLedgerSupportEnabled)
  }
}
