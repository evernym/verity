package com.evernym.verity.protocol.testkit

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.did.DidStr
import com.evernym.verity.ledger._
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.ledger.{LedgerAccess, LedgerAccessException, LedgerRejectException, LedgerUtil}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.SIGN_ED25519_SHA512_SINGLE
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccessAdapter
import com.evernym.verity.protocol.testkit.MockLedger.{TEST_INDY_SOVRIN_NAMESPACE, nonFqID}
import com.evernym.verity.testkit.TestWallet
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.{ExecutionContextProvider, Status}
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vdr._
import com.evernym.verity.vdr.base.{MOCK_VDR_DID_SOV_NAMESPACE, MOCK_VDR_SOV_NAMESPACE}
import org.json.JSONObject

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Try}

object MockableLedgerAccess {
  val MOCK_NO_DID = "MOCK_NO_DID"
  val MOCK_NOT_ENDORSER = "MOCK_NOT_ENDORSER"
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
  extends LedgerAccess with MockAsyncOpRunner {

  import MockableLedgerAccess._

  implicit def asyncAPIContext: AsyncAPIContext = AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)

  val testWallet = new TestWallet(executionContext, false)
  implicit val wap: WalletAPIParam = testWallet.wap
  override val walletAccess = new WalletAccessAdapter(
    testWallet.testWalletAPI,
    testWallet.walletId
  )

  lazy val invalidEndorserError: String = "Rule for this action is: 1 TRUSTEE signature is required OR 1 STEWARD " +
    "signature is required OR 1 ENDORSER signature is required OR 1 signature of any role is required with additional" +
    " metadata fees schema\\nFailed checks:\\nConstraint: 1 TRUSTEE signature is required, Error: Not enough TRUSTEE " +
    "signatures\\nConstraint: 1 STEWARD signature is required, Error: Not enough STEWARD signatures\\nConstraint: " +
    "1 ENDORSER signature is required, Error: Not enough ENDORSER signatures\\nConstraint: 1 signature of any role " +
    "is required with additional metadata fees schema, Error: Fees are required for this txn type"

  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String])
                               (handler: Try[PreparedTxn] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        val jsonObject = new JSONObject(schemaJson)
        endorser.foreach(eid => jsonObject.put("endorser", eid))
        val json = jsonObject.toString()
        submitterDids += json.hashCode -> submitterDID
        Try(PreparedTxn(TEST_INDY_SOVRIN_NAMESPACE, SIGN_ED25519_SHA512_SINGLE, json.getBytes, Array.empty, NO_ENDORSEMENT))
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def prepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FQCredDefId,
                                 submitterDID: DidStr,
                                 endorser: Option[String])
                                (handler: Try[PreparedTxn] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        val jsonObject = new JSONObject(credDefJson)
        endorser.foreach(eid => jsonObject.put("endorser", eid))
        val json = jsonObject.toString()
        submitterDids += json.hashCode -> submitterDID
        Try(PreparedTxn(TEST_INDY_SOVRIN_NAMESPACE, SIGN_ED25519_SHA512_SINGLE, json.getBytes, Array.empty, NO_ENDORSEMENT))
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
        if (submitterDID.equals(fqID(MOCK_NO_DID))) Failure(LedgerRejectException(s"verkey for $MOCK_NO_DID cannot be found"))
        else if (submitterDID.equals(fqID(MOCK_NOT_ENDORSER))) Failure(LedgerRejectException(invalidEndorserError))
        else Try(SubmittedTxn("{}"))
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveSchema(fqSchemaId: FQSchemaId, cacheOption: Option[CacheOption])(handler: Try[Schema] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        Try{
          val schemaResp = schemas.getOrElse(nonFqID(fqSchemaId), throw new Exception("Unknown schema"))
          Schema(fqSchemaId, schemaResp.json)
        }
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveSchemas(fqSchemaIds: Set[FQSchemaId], cacheOption: Option[CacheOption])(handler: Try[Seq[Schema]] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        Try{
          schemas.filter{ case (id, schema) => fqSchemaIds.map(nonFqID).contains(id)}.values.toSeq
        }
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveCredDef(fqCredDefId: FQCredDefId, cacheOption: Option[CacheOption])(handler: Try[CredDef] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        Try{
          val credDefResp = credDefs.getOrElse(nonFqID(fqCredDefId), throw new Exception("Unknown cred def"))
          CredDef(fqCredDefId, credDefResp.fqSchemaId, credDefResp.json)
        }
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveCredDefs(fqCredDefIds: Set[FQCredDefId], cacheOption: Option[CacheOption])(handler: Try[Seq[CredDef]] => Unit): Unit = {
    handler {
      if (ledgerAvailable) {
        Try{
          val unqualifiedCredDefIds = fqCredDefIds.map(nonFqID)
          val storedCredDefIds = credDefs.keys
          credDefs.filter{ case (id, credDef) => fqCredDefIds.map(nonFqID).contains(id)}.values.toSeq
        }
      }
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }


  private def extractSubmitterDID(preparedTxn: PreparedTxn): String = {
    val json = new String(preparedTxn.txnBytes)
    submitterDids(json.hashCode)
  }

  var submitterDids: Map[TxnHash, DidStr] = Map.empty

  val NAMESPACE_INDY_SOVRIN = "indy:sovrin"
  val NO_ENDORSEMENT = ""

  type TxnHash = Int

  override val mockExecutionContext: ExecutionContext = executionContext

  override def fqID(id: String): String = MockLedger.fqID(id)

  override def fqSchemaId(id: String): String = MockLedger.fqSchemaID(id)

  override def fqCredDefId(id: String): FQCredDefId = MockLedger.fqCredDefId(id)
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
            "55",
            "tag",
            "1.0",
            Map.empty
          )
        )
      )
  )


}

object MockLedger {

  val TEST_INDY_SOVRIN_NAMESPACE = MOCK_VDR_SOV_NAMESPACE

  val NO_ENDORSEMENT = ""

  def fqID(id: String): String = {
    LedgerUtil.toFQId(id, TEST_INDY_SOVRIN_NAMESPACE)
  }

  def fqSchemaID(id: String): String = {
    LedgerUtil.toFQSchemaId(id, TEST_INDY_SOVRIN_NAMESPACE)
  }

  def fqCredDefId(id: String): String = {
    LedgerUtil.toFQCredDefId(id, TEST_INDY_SOVRIN_NAMESPACE)
  }

  //TODO: come back to this
  def nonFqID(id: String): String = {
    id match {
      case fqIdRegEx(id)        => id
      case fqSchemaIdRegEx(id)  => nonFqID(id)
      case fqCredDefIdRegEx(id) => nonFqID(id).replace(s"$SCHEME_NAME_INDY_SCHEMA:$MOCK_VDR_DID_SOV_NAMESPACE:", "")
      case other                => id
    }
  }

  val fqIdRegEx = s"$SCHEME_NAME_DID:$TEST_INDY_SOVRIN_NAMESPACE:(.*)".r
  val fqSchemaIdRegEx = s"$SCHEME_NAME_INDY_SCHEMA:$MOCK_VDR_DID_SOV_NAMESPACE:(.*)".r
  val fqCredDefIdRegEx = s"$SCHEME_NAME_INDY_CRED_DEF:$MOCK_VDR_DID_SOV_NAMESPACE:(.*)".r

  def toFqId(id: String): String =
    LedgerUtil.toFQId(id, TEST_INDY_SOVRIN_NAMESPACE)
}
