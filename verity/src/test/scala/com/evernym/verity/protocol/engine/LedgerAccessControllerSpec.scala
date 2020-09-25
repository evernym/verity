package com.evernym.verity.protocol.engine

import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.ledger._
import com.evernym.verity.testkit.BasicSpec


import scala.util.Try

class LedgerAccessControllerSpec extends BasicSpec {

  "Ledger access controller" - {
    "when given correct access rights" - {
      "should pass the access right checks" in {
        val controller = new LedgerAccessController(Set(LedgerReadAccess), new TestLedger)
        controller.getCredDef("cred-def-id").isSuccess shouldBe true
        controller.getSchema("schema-id").isSuccess shouldBe true
      }
    }

    "when given wrong access rights" - {
      "should fail the access right checks" in {
        val controller = new LedgerAccessController(Set(), new TestLedger)
        controller.getCredDef("cred-def-id").isSuccess shouldBe false
        controller.getSchema("schema-id").isSuccess shouldBe false
      }
    }
  }

  class TestLedger extends LedgerAccess {
    override def walletAccess: WalletAccess = throw new NotImplementedError
    override def getCredDef(credDefId: String): Try[GetCredDefResp] = Try(GetCredDefResp(
      MockLedgerTxnExecutor.buildTxnResp("5XwZzMweuePeFZzArqvepR", None, None, "108"),
      Some(CredDefV1(
        "NcYxiDXkpYi6ov5FcYDi1e:3:CL:NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0:Tag1",
        "CL",
        "55",
        "tag",
        "1.0",
        Map.empty
      ))
    ))
    override def getSchema(schemaId: String): Try[GetSchemaResp] = Try(GetSchemaResp(
      MockLedgerTxnExecutor.buildTxnResp("5XwZzMweuePeFZzArqvepR", None, None, "107"),
      Some(SchemaV1(
        "NcYxiDXkpYi6ov5FcYDi1e:2:gvt:1.0",
        "schema-name",
        "0.1",
        Seq("attr-1","attr2"),
        Some(55),
        "0.1"
      ))
    ))

    override def writeSchema(submitterDID: String, schemaJson: String): Try[Either[StatusDetail, TxnResp]] = ???

    def writeCredDef(submitterDID: DID, credDefJson: String): Try[Either[StatusDetail, TxnResp]] = ???
  }
}
