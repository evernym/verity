package com.evernym.verity.metrics


import com.evernym.verity.actor.MetricsFilterCriteria
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.testkit.BasicSpec
import org.joda.time.Period
import org.joda.time.format.ISODateTimeFormat


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

    "when reset metrics before getting node metrics" - {
      "should be able get updated lastResetTimestamp" taggedAs (UNSAFE_IgnoreLog) in {
        MetricsReader.resetNodeMetrics()
        Thread.sleep(2000)

        val metricsAfterReset = MetricsReader.getNodeMetrics(MetricsFilterCriteria())

        metricsAfterReset.metadata.exists(_.nodeName.isEmpty) shouldBe false
        metricsAfterReset.metadata.exists(_.timestamp.isEmpty) shouldBe false
        metricsAfterReset.metadata.map(_.lastResetTimestamp).isDefined shouldBe true

        val timeStampAfterReset = ISODateTimeFormat.dateTime().parseDateTime(
          metricsAfterReset.metadata.get.timestamp).withMillisOfSecond(0)

        val lastResetTimeStampAfterReset = ISODateTimeFormat.dateTime().parseDateTime(
          metricsAfterReset.metadata.get.lastResetTimestamp).withMillisOfSecond(0)

        val diff = new Period(timeStampAfterReset, lastResetTimeStampAfterReset)
        assert(timeStampAfterReset.isAfter(lastResetTimeStampAfterReset))
        assert(diff.toString == "PT-2S")
      }
    }
  }

}
