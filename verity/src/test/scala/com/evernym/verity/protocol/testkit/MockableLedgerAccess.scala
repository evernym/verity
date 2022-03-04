package com.evernym.verity.protocol.testkit

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.did.DidStr
import com.evernym.verity.ledger._
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.ledger.{LedgerAccess, LedgerAccessException, LedgerRejectException}
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccessAdapter
import com.evernym.verity.testkit.TestWallet
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.{ExecutionContextProvider, Status}
import com.evernym.verity.vault.WalletAPIParam
import com.evernym.verity.vdr._
import org.json.JSONObject

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

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
                           val schemas: Map[String, GetSchemaResp] = MockLedgerData.schemas01,
                           val credDefs: Map[String, GetCredDefResp] = MockLedgerData.credDefs01,
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

  override def getCredDef(credDefId: String)(handler: Try[GetCredDefResp] => Unit): Unit = {
    handler {
      if (ledgerAvailable) Try(credDefs.getOrElse(credDefId, throw new Exception("Unknown cred def")))
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def writeCredDef(submitterDID: DidStr, credDefJson: String)(handler: Try[TxnResp] => Unit): Unit = {
    handler {
      if (ledgerAvailable & submitterDID.equals(MOCK_NO_DID)) Failure(LedgerRejectException(s"verkey for $MOCK_NO_DID cannot be found"))
      else if (ledgerAvailable & submitterDID.equals(MOCK_NOT_ENDORSER)) Failure(LedgerRejectException(invalidEndorserError))
      else if (ledgerAvailable) Success(TxnResp(submitterDID, None, None, "", None, 0, None))
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def getSchema(schemaId: String)(handler: Try[GetSchemaResp] => Unit): Unit = {
    handler {
      if (ledgerAvailable) Try(schemas.getOrElse(schemaId, throw new Exception("Unknown schema")))
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def writeSchema(submitterDID: DidStr, schemaJson: String)(handler: Try[TxnResp] => Unit): Unit = {
    handler {
      if (ledgerAvailable & submitterDID.equals(MOCK_NO_DID)) Failure(LedgerRejectException(s"verkey for $MOCK_NO_DID cannot be found"))
      else if (ledgerAvailable & submitterDID.equals(MOCK_NOT_ENDORSER)) Failure(LedgerRejectException(invalidEndorserError))
      else if (ledgerAvailable) Success(TxnResp(submitterDID, None, None, "", None, 0, None))
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def prepareSchemaForEndorsement(submitterDID: DidStr, schemaJson: String, endorserDID: DidStr)
                                          (handler: Try[LedgerRequest] => Unit): Unit = {
    handler {
      val json = new JSONObject(schemaJson)
      json.put("endorser", endorserDID)
      Try(LedgerRequest(json.toString))
    }
  }

  override def prepareCredDefForEndorsement(submitterDID: DidStr, credDefJson: String, endorserDID: DidStr)
                                           (handler: Try[LedgerRequest] => Unit): Unit = {
    handler {
      val json = new JSONObject(credDefJson)
      json.put("endorser", endorserDID)
      Try(LedgerRequest(json.toString))
    }
  }

  override def getSchemas(schemaIds: Set[String])(handler: Try[Map[String, GetSchemaResp]] => Unit): Unit = {
    handler {
      if (ledgerAvailable) Try(schemas.view.filterKeys(s => schemaIds.contains(s)).toMap)
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def getCredDefs(credDefIds: Set[String])(handler: Try[Map[String, GetCredDefResp]] => Unit): Unit = {
    handler {
      if (ledgerAvailable) Try(credDefs.view.filterKeys(c => credDefIds.contains(c)).toMap)
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def prepareSchemaTxn(schemaJson: String,
                                fqSchemaId: FQSchemaId,
                                submitterDID: DidStr,
                                endorser: Option[String])
                               (handler: Try[PreparedTxn] => Unit): Unit = {
    handler {
      if (ledgerAvailable) Try(PreparedTxn("namespace", NoSignature, schemaJson.getBytes, Array.empty, NoEndorsement))
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def prepareCredDefTxn(credDefJson: String,
                                 fqCredDefId: FQCredDefId,
                                 submitterDID: DidStr,
                                 endorser: Option[String])
                                (handler: Try[PreparedTxn] => Unit): Unit = {
    handler {
      if (ledgerAvailable) Try(PreparedTxn("namespace", NoSignature, credDefJson.getBytes, Array.empty, NoEndorsement))
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def submitTxn(preparedTxn: PreparedTxn,
                         signature: Array[Byte],
                         endorsement: Array[Byte])
                        (handler: Try[SubmittedTxn] => Unit): Unit = {
    handler {
      if (ledgerAvailable) Try(SubmittedTxn(""))
      else Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveSchema(fqSchemaId: FQSchemaId)(handler: Try[Schema] => Unit): Unit = {
    handler {
      // todo Use schema store to retrieve schema
      Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override def resolveCredDef(fqCredDefId: FQCredDefId)(handler: Try[CredDef] => Unit): Unit = {
    handler {
      // todo Use cred def store retrieve cred def
      Failure(LedgerAccessException(Status.LEDGER_NOT_CONNECTED.statusMsg))
    }
  }

  override val mockExecutionContext: ExecutionContext = executionContext
}


object MockLedgerData {
  val txnResp = MockLedgerTxnExecutor.buildTxnResp("5XwZzMweuePeFZzArqvepR", None, None, "107")

  val schemas01 = Map(
    "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0" ->
      GetSchemaResp(
        txnResp,
        Some(SchemaV1(
          "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
          "schema-name",
          "0.1",
          Seq("attr-1", "attr2"),
          Some(55),
          "0.1"
        ))
      )

  )

  val credDefs01 = Map(
    "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1" ->
      GetCredDefResp(
        txnResp,
        Some(CredDefV1(
          "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1",
          "CL",
          "55",
          "tag",
          "1.0",
          Map.empty
        ))
      )
  )
}

