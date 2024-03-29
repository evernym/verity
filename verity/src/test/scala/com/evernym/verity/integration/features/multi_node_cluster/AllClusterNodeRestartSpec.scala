package com.evernym.verity.integration.features.multi_node_cluster

import com.evernym.verity.integration.base.{VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}


class AllClusterNodeRestartSpec
  extends VerityProviderBaseSpec
    with SdkProvider
    with Eventually {

  lazy val verityEnv = VerityEnvBuilder(nodeCount = 3).build(VAS)
  lazy val issuerSDK = setupIssuerSdk(verityEnv, executionContext)

  "VerityAdmin" - {

    "with multi node clustered" - {

      "when checked if all nodes are up" - {
        "should be successful" in {
          eventually(timeout(Span(40, Seconds)), interval(Span(200, Millis))) {
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
          val restartFutures = verityEnv.availableNodes.map(n => n.restart())
          val future = Future.sequence(restartFutures)
          assert(future.isReadyWithin(30.seconds), "Cluster restart failed")
          verityEnv.availableNodes.size shouldBe 3
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
