package com.evernym.verity.protocol.engine

import akka.actor.{ActorRef, ActorSystem}
import com.evernym.verity.actor.testkit.{AkkaTestBasic, TestAppConfig}
import com.evernym.verity.actor.testkit.actor.{ActorSystemVanilla, MockLedgerSvc, MockLedgerTxnExecutor}
import com.evernym.verity.cache.base.Cache
import com.evernym.verity.did.DidStr
import com.evernym.verity.ledger._
import com.evernym.verity.observability.metrics.NoOpMetricsWriter
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.ledger.LedgerAccessAPI
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, LedgerReadAccess}
import com.evernym.verity.protocol.engine.asyncapi.ledger.LedgerAccessController
import com.evernym.verity.protocol.testkit.MockableWalletAccess
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.Status
import com.evernym.verity.vdr.service.{IndyLedger, VDRToolsConfig}
import com.evernym.verity.vdr.{CredDef, FQCredDefId, FQSchemaId, PreparedTxn, Schema, SubmittedTxn, TestVDRTools, VDRActorAdapter, VDRAdapter, VDRToolsFactoryParam}

import scala.concurrent.ExecutionContext
import scala.util.Try

class LedgerAccessControllerSpec
  extends BasicSpec
    with MockAsyncOpRunner {
  val executionContext: ExecutionContext = TestExecutionContextProvider.ecp.futureExecutionContext

  val actorSystem: ActorSystem = ActorSystemVanilla("test")
  lazy val generalCache: Cache = new Cache("GC", Map(), NoOpMetricsWriter(), executionContext)

  val vdrImpl: VDRAdapter = new MockVDRAdapter()

  implicit def asyncAPIContext: AsyncAPIContext =
    AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)


  "Ledger access controller" - {
    "when given correct access rights" - {
      "should pass the access right checks" in {
        val controller = ledgerAPI(generalCache)
        controller.getCredDef("cred-def-id") { r => r.isSuccess shouldBe true}
        controller.getSchema("schema-id") { r => r.isSuccess shouldBe true }
      }
    }

    "when given wrong access rights" - {
      "should fail the access right checks" in {
        val controller = ledgerAPI(generalCache)
        controller.getCredDef("cred-def-id") { r => r.isSuccess shouldBe false }
        controller.getSchema("schema-id") { r => r.isSuccess shouldBe false }
      }
    }
  }

  def ledgerAPI(cache: Cache, wa: WalletAccess = MockableWalletAccess()): LedgerAccessController = {
    implicit val ec: ExecutionContext = executionContext
    val indyLedger = IndyLedger(List("indy:sovrin", "sov"), "genesis1-path", None)
    val vdrToolsConfig = VDRToolsConfig("/usr/lib", List(indyLedger))
    val vdrToolFactory = { _: VDRToolsFactoryParam => new TestVDRTools }
    import akka.actor.typed.scaladsl.adapter._

    new LedgerAccessController(
      vdrImpl,
      cache,
      new MockLedgerSvc(AkkaTestBasic.system(), executionContext),
      wa
    ){

      override def walletAccess: WalletAccess = wa

      override def getCredDef(credDefId: String)(handler: Try[GetCredDefResp] => Unit): Unit =
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

      override def getSchema(schemaId: String)(handler: Try[GetSchemaResp] => Unit): Unit =
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

      override def getSchemas(schemaIds: Set[String])(handler: Try[Map[String, GetSchemaResp]] => Unit): Unit = ???

      override def getCredDefs(credDefIds: Set[String])(handler: Try[Map[String, GetCredDefResp]] => Unit): Unit = ???

      override def writeSchema(submitterDID: DidStr, schemaJson: String)(handler: Try[Either[Status.StatusDetail, TxnResp]] => Unit): Unit = ???

      override def prepareSchemaForEndorsement(submitterDID: DidStr, schemaJson: String, endorserDID: DidStr)(handler: Try[LedgerRequest] => Unit): Unit = ???

      override def writeCredDef(submitterDID: DidStr, credDefJson: String)(handler: Try[Either[Status.StatusDetail, TxnResp]] => Unit): Unit = ???

      override def prepareCredDefForEndorsement(submitterDID: DidStr, credDefJson: String, endorserDID: DidStr)(handler: Try[LedgerRequest] => Unit): Unit = ???

      override def prepareSchemaTxn(schemaJson: String, fqSchemaId: FQSchemaId, submitterDID: DidStr, endorser: Option[String])(handler: Try[PreparedTxn] => Unit): Unit = ???

      override def prepareCredDefTxn(credDefJson: String, fqCredDefId: FQCredDefId, submitterDID: DidStr, endorser: Option[String])(handler: Try[PreparedTxn] => Unit): Unit = ???

      override def submitTxn(preparedTxn: PreparedTxn, signature: Array[Byte], endorsement: Array[Byte])(handler: Try[SubmittedTxn] => Unit): Unit = ???

      override def resolveSchema(fqSchemaId: FQSchemaId)(handler: Try[Schema] => Unit): Unit = ???

      override def resolveCredDef(fqCredDefId: FQCredDefId)(handler: Try[CredDef] => Unit): Unit = ???

      override def accessRights: Set[AccessRight] = ???
    }
  }
}
