package com.evernym.verity.integration.multi_node_cluster

import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.util.TestExecutionContextProvider

import scala.concurrent.ExecutionContext


class BasicMultiNodeClusterSpec
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

      "when try to stop one of the node" - {
        "should be successful" in {
          verityEnv.stopNodeAtIndex(1)
          verityEnv.availableNodes.size shouldBe 2
        }
      }

      "when tried to fetch agency key after one node stop" - {
        "should be still successful" in {
          issuerSDK.fetchAgencyKey()
        }
      }

      "when try to stop one more node" - {
        "should be successful" in {
          verityEnv.stopNodeAtIndex(0)
          verityEnv.availableNodes.size shouldBe 1
        }
      }

      "when tried to fetch agency key after two node stop" - {
        "should be still successful" in {
          issuerSDK.fetchAgencyKey()
        }
      }

      "when try to stop the last node" - {
        "should be successful" in {
          verityEnv.stopNodeAtIndex(2)
          verityEnv.availableNodes.size shouldBe 0
        }
      }

      "when tried to fetch agency key after all node stop" - {
        "should be still successful" in {
          val ex = intercept[RuntimeException] {
            issuerSDK.fetchAgencyKey()
          }
          ex.getMessage shouldBe "no verity node available"
        }
      }

      "when started first seed node" - {
        "should be successful" in {
          verityEnv.startNodeAtIndex(0)
          verityEnv.availableNodes.size shouldBe 1
        }
      }

      "when tried to fetch agency key after only first node is up" - {
        "should be successful" in {
          issuerSDK.fetchAgencyKey()
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


