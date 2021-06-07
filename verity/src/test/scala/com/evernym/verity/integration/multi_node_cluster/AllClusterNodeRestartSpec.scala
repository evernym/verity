package com.evernym.verity.integration.multi_node_cluster

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.Future


class AllClusterNodeRestartSpec
  extends VerityProviderBaseSpec
    with SdkProvider
    with Eventually {

  lazy val verityEnv = VerityEnvBuilder.default(nodeCount = 3).build()
  lazy val issuerSDK = setupIssuerSdk(verityEnv)

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

}
