package com.evernym.verity.integration.multi_node_cluster

import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

import scala.util.Random


class BasicMultiNodeClusterSpec
  extends VerityProviderBaseSpec
    with SdkProvider
    with Eventually {

  lazy val verityEnv = setupNewVerityEnv(nodeCount = 3)
  lazy val issuerSDK = setupIssuerSdk(verityEnv)

  "VerityAdmin" - {

    "with multi node clustered" - {

      "when checked if all nodes are up" - {
        "should be successful" in {
          eventually(timeout(Span(20, Seconds)), interval(Span(3, Seconds))) {
            verityEnv.checkIfNodesAreUp() shouldBe true
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
          verityEnv.stopNodeAtIndex(Random.nextInt(verityEnv.availableNodes.size-1))
        }
      }

      "when tried to fetch agency key after one node stop" - {
        "should be still successful" in {
          issuerSDK.fetchAgencyKey()
        }
      }

      "when checked if all available nodes are still up" - {
        "should be successful" in {
          Thread.sleep(5000)
          verityEnv.availableNodes.size shouldBe 2
          verityEnv.checkIfNodesAreUp(verityEnv.availableNodes) shouldBe true
        }
      }
    }
  }

}


