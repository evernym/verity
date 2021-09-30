package com.evernym.verity.vdr

import akka.testkit.TestKitBase
import com.evernym.verity.actor.testkit.HasBasicActorSystem
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.vdr.service.{IndyLedger, Ledger, VDRToolsConfig}
import org.scalatest.concurrent.Eventually

import scala.concurrent.ExecutionContext


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
        testVDR.ledgerRegistry.ledgers.size shouldBe 1
      }
    }
  }

  def createVDRActorAdapter(ledgers: List[Ledger]): VDRActorAdapter = {
    val testVDRCreator = { _: CreateVDRParam => testVDR }
    val vdrToolsConfig = VDRToolsConfig("/usr/lib", ledgers)
    new VDRActorAdapter(testVDRCreator, vdrToolsConfig, None)(ec, system)
  }

  lazy val testVDR = new TestVDR
  lazy val defaultIndyLedger: IndyLedger = IndyLedger(List("indy:sovrin", "sov"), "genesis1-path", None)
  lazy val anotherIndyLedger: IndyLedger = IndyLedger(List("indy:sovrin", "sov"), "genesis2-path", None)

  implicit lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  implicit val ec: ExecutionContext = ecp.futureExecutionContext
}
