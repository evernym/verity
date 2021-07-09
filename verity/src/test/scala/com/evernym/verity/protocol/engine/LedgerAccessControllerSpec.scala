package com.evernym.verity.protocol.engine

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.{AkkaTestBasic, TestAppConfig}
import com.evernym.verity.actor.testkit.actor.{MockLedgerSvc, MockLedgerTxnExecutor}
import com.evernym.verity.cache.base.Cache
import com.evernym.verity.ledger._
import com.evernym.verity.metrics.MetricsWriterExtensionImpl
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.ledger.LedgerAccessAPI
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.asyncapi.LedgerReadAccess
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccessController
import com.evernym.verity.protocol.testkit.MockableWalletAccess
import com.evernym.verity.testkit.BasicSpec

import scala.util.Try

class LedgerAccessControllerSpec
  extends BasicSpec
    with MockAsyncOpRunner {

  lazy val generalCache: Cache = new Cache("GC", Map(), new MetricsWriterExtensionImpl())

  implicit def asyncAPIContext: AsyncAPIContext =
    AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)


  "Ledger access controller" - {
    "when given correct access rights" - {
      "should pass the access right checks" in {
        val controller = new LedgerAccessController(Set(LedgerReadAccess), ledgerAPI(generalCache))
        controller.getCredDef("cred-def-id") { r => r.isSuccess shouldBe true}
        controller.getSchema("schema-id") { r => r.isSuccess shouldBe true }
      }
    }

    "when given wrong access rights" - {
      "should fail the access right checks" in {
        val controller = new LedgerAccessController(Set(), ledgerAPI(generalCache))
        controller.getCredDef("cred-def-id") { r => r.isSuccess shouldBe false }
        controller.getSchema("schema-id") { r => r.isSuccess shouldBe false }
      }
    }
  }

  def ledgerAPI(cache: Cache, wa: WalletAccess = MockableWalletAccess()): LedgerAccessAPI =
    new LedgerAccessAPI(cache, new MockLedgerSvc(AkkaTestBasic.system()), wa){

    override def walletAccess: WalletAccess = wa

    override def runGetCredDef(credDefId: String): Unit =
      Try(GetCredDefResp(
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

    override def runGetSchema(schemaId: String): Unit =
      Try(GetSchemaResp(
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

    override def runGetSchemas(schemaIds: Set[String]): Unit = ???

    override def runGetCredDefs(credDefIds: Set[String]): Unit = ???

    override def runWriteSchema(submitterDID: DID, schemaJson: String): Unit = ???

    override def runPrepareSchemaForEndorsement(submitterDID: DID, schemaJson: String, endorserDID: DID): Unit = ???

    override def runWriteCredDef(submitterDID: DID, credDefJson: String): Unit = ???

    override def runPrepareCredDefForEndorsement(submitterDID: DID, credDefJson: String, endorserDID: DID): Unit = ???
  }
}
