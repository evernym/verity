package com.evernym.verity.vdr

import akka.testkit.TestKitBase
import akka.actor.typed.scaladsl.adapter._
import com.evernym.verity.actor.testkit.HasBasicActorSystem
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vdr.service.{IndyLedger, Ledger, VDRToolsConfig}
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._


class VDRActorAdapterSpec
  extends TestKitBase
    with HasBasicActorSystem
    with BasicSpec
    with Eventually {


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
            "sov"         -> LedgerStatus(reachable = true)
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
      "should result in failure" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.prepareSchemaTxn(
              "schemaJson",
              "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1",
              "did1",
              None
            ),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "invalid fq did: did1"
      }
    }

    "when asked to prepare schema txn with non fqSchemaId" - {
      "should result in failure" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.prepareSchemaTxn(
              "schemaJson",
              "F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1",
              "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
              None
            ),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "invalid identifier: F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1"
      }
    }

    "when asked to prepare schema txn with valid data" - {
      "should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        Await.result(
          vdrAdapter.prepareSchemaTxn(
            "schemaJson",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1",
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
            """{"field1":"value"1}""",
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

    "when asked to resolve schema for invalid schema id" - {
      "it should fail" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.resolveSchema("did1"),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "invalid fq did: did1"
      }
    }

    "when asked to resolve schema for valid schema id" - {
      "it should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val result = for {
          preparedTxn <- vdrAdapter.prepareSchemaTxn(
            """{"field1":"value"1}""",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
            None
          )
          _ <- vdrAdapter.submitTxn(preparedTxn, "signature".getBytes, Array.empty)
          schema <- vdrAdapter.resolveSchema("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1")
        } yield {
          schema.fqId shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1"
          schema.json shouldBe """{"field1":"value"1}"""
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
              "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1",
              "did1",
              None
            ),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "invalid fq did: did1"
      }
    }

    "when asked to prepare cred def txn with non fqSchemaId" - {
      "should result in failure" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val ex = intercept[RuntimeException] {
          Await.result(
            vdrAdapter.prepareCredDefTxn(
              """{"schemaId":"schema-id"}""",
              "F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1",
              "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
              None
            ),
            apiTimeout
          )
        }
        ex.getMessage shouldBe "invalid identifier: F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1"
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
        ex.getMessage shouldBe "invalid fq did: did1"
      }
    }

    "when asked to resolve cred def for valid schema id" - {
      "it should be successful" in {
        val vdrAdapter = createVDRActorAdapter(List(defaultIndyLedger))
        val result = for {
          preparedTxn <- vdrAdapter.prepareCredDefTxn(
            """{"schemaId":"schema-id"}""",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1",
            "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM",
            None
          )
          _ <- vdrAdapter.submitTxn(preparedTxn, "signature".getBytes, Array.empty)
          credDef <- vdrAdapter.resolveCredDef("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1")
        } yield {
          credDef.fqId shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM:2:degree:1.1.1"
          credDef.json shouldBe """{"schemaId":"schema-id"}"""
          credDef.schemaId shouldBe "schema-id"
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
        ex.getMessage shouldBe "invalid fq did: did1"
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

        //add did doc to  the VDR (as of now, we don't have prepareDIDTxn support, so directly adding it)
        Await.result(
          testVDRTools.addDidDoc(
            TestVDRDidDoc("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM", "verKey", None)
          ),
          apiTimeout
        )

        val dd = Await.result(
            vdrAdapter.resolveDID("did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM"),
            apiTimeout
        )
        dd.fqId shouldBe "did:indy:sovrin:F72i3Y3Q4i466efjYJYCHM"
        dd.verKey shouldBe "verKey"
        dd.endpoint shouldBe None
      }
    }
  }

  def createVDRActorAdapter(ledgers: List[Ledger]): VDRActorAdapter = {
    testVDRTools = new TestVDRTools
    val testVDRToolsFactory = { _: VDRToolsFactoryParam => testVDRTools }
    val vdrToolsConfig = VDRToolsConfig("/usr/lib", ledgers)
    new VDRActorAdapter(testVDRToolsFactory, vdrToolsConfig, None)(ec, system.toTyped)
  }

  lazy val apiTimeout: FiniteDuration = 5.seconds

  lazy val defaultIndyLedger: IndyLedger = IndyLedger(List("indy:sovrin", "sov"), "genesis1-path", None)
  lazy val anotherIndyLedger: IndyLedger = IndyLedger(List("indy:sovrin", "sov"), "genesis2-path", None)

  implicit lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  implicit val ec: ExecutionContext = ecp.futureExecutionContext

  def addDidDoc(dd: TestVDRDidDoc): Unit = {
    testVDRTools.addDidDoc(dd)
  }

  var testVDRTools = new TestVDRTools
}
