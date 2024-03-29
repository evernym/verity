package com.evernym.verity.protocol.protocols

import com.evernym.verity.actor.agent.{AgentProvHelper, SponsorRel}
import com.evernym.verity.constants.Constants.MFV_0_7
import com.evernym.verity.observability.metrics.CustomMetrics.AS_NEW_PROTOCOL_COUNT
import com.evernym.verity.observability.metrics.MetricHelpers.{SPONSOR_ID, SPONSOR_ID2, getMetricWithTags}
import com.evernym.verity.observability.metrics.MetricWithTags
import com.evernym.verity.observability.metrics.CustomMetrics
import com.evernym.verity.protocol.engine.Constants.MSG_FAMILY_AGENT_PROVISIONING
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext


trait ProtocolMetricsSpec

class AriesProtocolMetricSpec
  extends ProtocolMetricsSpec
    with AgentProvHelper {

  def agentProvisioningProtocolMetric(): Unit = {
    CustomMetrics.initGaugeMetrics(platform.agentActorContext.metricsWriter)
    "when protocol created" - {
      "should record metric for provisioning" in {
        val protoRef = s"$MSG_FAMILY_AGENT_PROVISIONING[$MFV_0_7]"
        val tag: Map[String, String] = Map("proto-ref" -> protoRef, "sponsorId" -> "", "sponseeId" -> "")
        val count = numberOfTags(getMetrics(AS_NEW_PROTOCOL_COUNT), tag)

        val a1 = newEdgeAgent()
        val a2 = newEdgeAgent()

        createCloudAgent(SponsorRel(SPONSOR_ID, "id"), testSponsor.verKey, getNonce, a1)
        assert(numberOfTags(getMetrics(AS_NEW_PROTOCOL_COUNT), tag) == count + 1)

        createCloudAgent(SponsorRel(SPONSOR_ID2, "id2"), testSponsor.verKey, getNonce, a2)
        assert(numberOfTags(getMetrics(AS_NEW_PROTOCOL_COUNT), tag) == count + 2)
      }
    }

    def getMetrics(key: String): MetricWithTags =
      getMetricWithTags(Set(key), testMetricsBackend)(key)

    def numberOfTags(baseMetric: MetricWithTags, tagMap: Map[String, String]): Double =
      baseMetric.tags.filter(x => x._1 == tagMap).getOrElse(tagMap, 0.0)

  }

  agentProvisioningProtocolMetric()

  override def overrideSpecificConfig: Option[Config] = Option {
    ConfigFactory parseString {
      """
        verity.metrics {
          protocol {
            tags {
              uses-sponsor = true
              uses-sponsee = true
            }
          }
        }
        """.stripMargin
    }
  }
  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def executionContextProvider: ExecutionContextProvider = ecp

}
