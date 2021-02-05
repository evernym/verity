package com.evernym.verity.metrics


import com.evernym.verity.actor.MetricsFilterCriteria
import com.evernym.verity.testkit.BasicSpec


class MetricsReporterApiSpec extends BasicSpec {

  "Metrics" - {

    "when tried to get node metrics" - {
      "should return metrics data" in {
        val nmd = MetricsReader.getNodeMetrics(MetricsFilterCriteria())
        nmd.metadata.get.nodeName.isEmpty shouldBe false
        nmd.metadata.get.timestamp.isEmpty shouldBe false
        Option(nmd.metadata.get.lastResetTimestamp).isDefined shouldBe true
      }
    }

    "when tried to get node metrics without metadata" - {
      "should return metrics without metadata" in {
        val nmd = MetricsReader.getNodeMetrics(MetricsFilterCriteria(includeMetaData = false))
        nmd.metadata shouldBe None
      }
    }
  }

}
