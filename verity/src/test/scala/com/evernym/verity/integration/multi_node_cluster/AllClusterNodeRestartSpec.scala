package com.evernym.verity.integration.multi_node_cluster

import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.ExecutionContext


class AllClusterNodeRestartSpec
  extends VerityProviderBaseSpec
    with SdkProvider
    with Eventually {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val verityEnv = VerityEnvBuilder.default(nodeCount = 3).build(VAS)
  lazy val issuerSDK = setupIssuerSdk(verityEnv, executionContext, ecp.walletFutureExecutionContext)

  "VerityAdmin" - {

    "with multi node clustered" - {

      "when checked if all nodes are up" - {
        "should be successful" in {
          eventually(timeout(Span(20, Seconds)), interval(Span(200, Millis))) {
            verityEnv.checkIfNodesAreUp() shouldBe true
            verityEnv.availableNodes.size shouldBe 3
          }
        }
      }

      "when tried to fetch agency key multiple times" - {
        "should be successful" in {
          verityEnv.nodes.foreach { _ =>
            issuerSDK.fetchAgencyKey()
          }
        }
      }

      "when try to restart all nodes" - {
        "should be successful" in {
          verityEnv.availableNodes.foreach(node => node.restart())
          eventually(timeout(Span(30, Seconds)), interval(Span(100, Millis))) {
            verityEnv.availableNodes.size shouldBe 3
          }
        }
      }

      "when tried to fetch agency key after whole cluster restart" - {
        "should be successful" in {
          verityEnv.nodes.foreach { _ =>
            issuerSDK.fetchAgencyKey()
          }
        }
      }
    }
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}
