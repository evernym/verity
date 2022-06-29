package com.evernym.verity.vdr

import akka.actor.typed.scaladsl.adapter._
import akka.testkit.TestKitBase
import com.evernym.verity.actor.testkit.HasBasicActorSystem
import com.evernym.verity.protocol.testkit.MockLedger.{TEST_INDY_LEDGER_PREFIX, TEST_INDY_SOVRIN_NAMESPACE, ledgerPrefixMappings}
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vdr.base.TestVDRDidDoc
import com.evernym.verity.vdr.service.{IndyLedger, Ledger, VDRToolsConfig}
import org.json.JSONObject
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}


class VDRActorAdapterSpec
  extends TestKitBase
    with HasBasicActorSystem
    with BasicSpec
    with Eventually
    with BeforeAndAfterEach {

  var testLedgerRegistry: MockLedgerRegistry = MockLedgerRegistry()

  override protected def afterEach(): Unit = {
    testLedgerRegistry.cleanup()
  }

  "VDRActorAdapter" - {

    "when created with invalid configuration" - {
      "should throw an error" in {
        val ex = intercept[RuntimeException] {
          createVDRActorAdapter(
            List(
              defaultIndyLedger,
              anotherIndyLedger
            )
          )
        }
        ex.getMessage shouldBe "[VDR] ledgers can not have shared namespaces"
      }
    }

    "when created with valid configuration" - {
      "should be successful" in {
        createVDRActorAdapter(List(defaultIndyLedger))
      }
    }

    "when pinged with empty namespaces" - {
      "should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val result = Await.result(vdrAdapter.ping(List.empty), apiTimeout)
        result shouldBe PingResult(
          Map(
            "indy:sovrin" -> LedgerStatus(reachable = true),
            "sov" -> LedgerStatus(reachable = true)
          )
        )
      }
    }

    "when pinged with specific namespaces" - {
      "should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val result = Await.result(vdrAdapter.ping(List("indy:sovrin")), apiTimeout)
        result shouldBe PingResult(
          Map(
            "indy:sovrin" -> LedgerStatus(reachable = true)
          )
        )
      }
    }

    "when asked to prepare schema txn with non fqdid" - {
      "should be still successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.prepareSchemaTxn(
              "{}",
              "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1",
              "did1",
              None
            ),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "could not extract namespace for given identifier: Some(did1) (vdrUnqualifiedLedgerPrefix: None)"
      }
    }

    "when asked to prepare schema txn with non fqSchemaId" - {
      "should result in failure" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.prepareSchemaTxn(
              "{}",
              "F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/schema-name/1.2.3",
              "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
              None
            ),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "non fully qualified schema id: F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/schema-name/1.2.3"
      }
    }

    "when asked to prepare schema txn with valid data" - {
      "should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        Await.result(
          vdrAdapter.prepareSchemaTxn(
            "{}",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
            None
          ),
          apiTimeout
        )
      }
    }

    "when asked to submit schema txn with valid data" - {
      "should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val result = for {
          preparedTxn <- vdrAdapter.prepareSchemaTxn(
            """{"field1":"value1"}""",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
            None
          )
          _ <- vdrAdapter.submitTxn(preparedTxn, "signature".getBytes, Array.empty)
        } yield {
          //nothing to validate
        }
        Await.result(result, apiTimeout)
      }
    }

    "when asked to submit schema txn without submitter DID on ledger" - {
      "should respond with appropriate error" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val result = for {
          preparedTxn <- vdrAdapter.prepareSchemaTxn(
            """{"field1":"value1"}""",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
            None
          )
          _ <- vdrAdapter.submitTxn(preparedTxn, "signature".getBytes, Array.empty)
        } yield {
          //nothing to validate
        }
        Await.result(result, apiTimeout)
      }
    }

    "when asked to resolve schema for invalid schema id" - {
      "it should fail" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.resolveSchema("did1"),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "could not extract namespace for given identifier: Some(did1) (vdrUnqualifiedLedgerPrefix: None)"
      }
    }

    "when asked to resolve schema for non existent one" - {
      "it should fail" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.resolveSchema("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1"),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "schema not found for given id: did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1"
      }
    }

    "when asked to resolve schema for valid schema id" - {
      "it should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val result = for {
          preparedTxn <- vdrAdapter.prepareSchemaTxn(
            """{"field1":"value1"}""",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
            None
          )
          _ <- vdrAdapter.submitTxn(preparedTxn, "signature".getBytes, Array.empty)
          schema <- vdrAdapter.resolveSchema("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1")
        } yield {
          schema.fqId shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1"
          val json = new JSONObject(schema.json)
          json.getString("field1") shouldBe "value1"
          json.getString("id") shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1"
        }
        Await.result(result, apiTimeout)
      }
    }

    "when asked to prepare cred def txn with non fqdid" - {
      "should result in failure" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.prepareCredDefTxn(
              """{"schemaId":"schema-id"}""",
              "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:/anoncreds/v0/CLAIM_DEF/466/cred-def-name",
              "did1",
              None
            ),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "could not extract namespace for given identifier: Some(did1) (vdrUnqualifiedLedgerPrefix: None)"
      }
    }

    "when asked to prepare cred def txn with non fqSchemaId" - {
      "should result in failure" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.prepareCredDefTxn(
              """{"schemaId":"schema-id"}""",
              "F72i3Y3Q4i466efjYJYCHM:/anoncreds/v0/CLAIM_DEF/466/cred-def-name",
              "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
              None
            ),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "non fully qualified cred def id: F72i3Y3Q4i466efjYJYCHM:/anoncreds/v0/CLAIM_DEF/466/cred-def-name"
      }
    }

    "when asked to prepare cred def txn with valid data" - {
      "should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        Await.result(
          vdrAdapter.prepareCredDefTxn(
            """{"schemaId":"schema-id"}""",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
            None
          ),
          apiTimeout
        )
      }
    }

    "when asked to submit cred def txn with valid data" - {
      "should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val result = for {
          preparedTxn <- vdrAdapter.prepareCredDefTxn(
            """{"schemaId":"schema-id"}""",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
            None
          )
          _ <- vdrAdapter.submitTxn(preparedTxn, "signature".getBytes, Array.empty)
        } yield {
          //nothing to validate
        }
        Await.result(result, apiTimeout)
      }
    }

    "when asked to resolve cred def for invalid cred def id" - {
      "it should fail" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.resolveCredDef("did1"),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "could not extract namespace for given identifier: Some(did1) (vdrUnqualifiedLedgerPrefix: None)"
      }
    }

    "when asked to resolve cred def for non existent one" - {
      "it should fail" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.resolveCredDef("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/CLAIM_DEF/466/cred-def-name"),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "cred def not found for given id: did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/CLAIM_DEF/466/cred-def-name"
      }
    }

    "when asked to resolve cred def for valid schema id" - {
      "it should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val result = for {
          preparedTxn <- vdrAdapter.prepareCredDefTxn(
            """{"schemaId":"did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1"}""",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/CLAIM_DEF/466/cred-def-name",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
            None
          )
          _ <- vdrAdapter.submitTxn(preparedTxn, "signature".getBytes, Array.empty)
          credDef <- vdrAdapter.resolveCredDef("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/CLAIM_DEF/466/cred-def-name")
        } yield {
          credDef.fqId shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/CLAIM_DEF/466/cred-def-name"
          credDef.fqSchemaId shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1"
          val json = new JSONObject(credDef.json)
          json.getString("schemaId") shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/SCHEMA/degree/1.1.1"
          json.getString("id") shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM/anoncreds/v0/CLAIM_DEF/466/cred-def-name"
        }
        Await.result(result, apiTimeout)
      }
    }

    "when asked to resolve DID with invalid did" - {
      "should result in failure" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.resolveDID("did1"),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "could not extract namespace for given identifier: Some(did1) (vdrUnqualifiedLedgerPrefix: None)"
      }
    }

    "when asked to resolve DID for non existent one" - {
      "should result in failure" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.resolveDID("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM"),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "did doc not found for given id: did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM (available did docs: )"
      }
    }

    "when asked to resolve DID with valid did" - {
      "should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))

        //make sure all ledgers are registered
        eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
          val result = Await.result(vdrAdapter.ping(List.empty), apiTimeout)
          result shouldBe PingResult(
            Map(
              "indy:sovrin" -> LedgerStatus(reachable = true),
              "sov" -> LedgerStatus(reachable = true)
            )
          )
        }

        for {
          //add did doc to  the VDR (as of now, we don't have prepareDIDTxn support, so directly adding it)
          _ <- testLedgerRegistry.addDidDoc(TestVDRDidDoc("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM", "verKey", None));
          dd <- vdrAdapter.resolveDID("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM")
        } yield {
          dd.fqId shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM"
          dd.verKey shouldBe "verKey"
          dd.endpoint shouldBe None
        }
      }
    }
  }

  def createVDRActorAdapter(ledgers: List[Ledger]): VDRActorAdapter = {

    val testVdrToolsBuilder = new MockVdrToolsBuilder(testLedgerRegistry)
    val testVDRToolsFactory = { () => testVdrToolsBuilder }
    val vdrToolsConfig = VDRToolsConfig(TEST_INDY_LEDGER_PREFIX, ledgerPrefixMappings, ledgers)
    new VDRActorAdapter(testVDRToolsFactory, vdrToolsConfig, None)(ec, system.toTyped)
  }

  lazy val apiTimeout: FiniteDuration = 5.seconds

  lazy val defaultIndyLedger: IndyLedger = IndyLedger(List("indy:sovrin", "sov"), "genesis1-path", None)
  lazy val anotherIndyLedger: IndyLedger = IndyLedger(List("indy:sovrin", "sov", "cheqd"), "genesis2-path", None)

  implicit lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  implicit val ec: ExecutionContext = ecp.futureExecutionContext

}
