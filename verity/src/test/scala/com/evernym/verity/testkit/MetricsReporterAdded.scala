package com.evernym.verity.testkit

import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.metrics.reporter.{KamonPrometheusMetricsReporter, MetricDetail}
import org.scalatest.{BeforeAndAfterEach, Suite}

//adds kamon prometheus metrics reporter to be used/queried by main/test code
trait AddMetricsReporter {
  MetricsReader   //adds/initialized metrics reporter

  def getFilteredMetric(nameStartsWith: String,
                        tags: Map[String, String]=Map.empty): Option[MetricDetail] = {
    getFilteredMetrics(nameStartsWith, tags).headOption
  }

  def getFilteredMetrics(nameStartsWith: String,
                         tags: Map[String, String]=Map.empty): List[MetricDetail] = {
    val currentMetrics = MetricsReader.getNodeMetrics().metrics

    currentMetrics.filter { m =>
      m.isNameStartsWith(nameStartsWith) &&
        (tags.isEmpty || tags.toSet.subsetOf(m.tags.getOrElse(Map.empty).toSet))
    }
  }
}

//adds kamon prometheus metrics reporter to be used/queried by main/test code
// and also it gets reset before each test
trait ResetMetricsReporterBeforeEach
  extends AddMetricsReporter
    with BeforeAndAfterEach
    with Matchers { this : Suite =>

  override def beforeEach(): Unit = {
    KamonPrometheusMetricsReporter._resetFixedMetricsReporter()
    MetricsReader.getNodeMetrics().metrics.size shouldBe 0
    super.beforeEach()
  }
}