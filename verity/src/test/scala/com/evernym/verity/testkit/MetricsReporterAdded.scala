package com.evernym.verity.testkit

import org.scalatest.{BeforeAndAfterEach, Suite}

//@Deprecated
//trait MetricsReadHelper {
//
//  def getFilteredMetric(nameStartsWith: String,
//                        tags: Map[String, String]=Map.empty): Option[MetricDetail] = {
//    getFilteredMetrics(nameStartsWith, tags).headOption
//  }
//
//  def getFilteredMetrics(nameStartsWith: String,
//                         tags: Map[String, String]=Map.empty): List[MetricDetail] = {
//    val currentMetrics = MetricsReader.getNodeMetrics().metrics
//
//    currentMetrics.filter { m =>
//      m.isNameStartsWith(nameStartsWith) &&
//        (tags.isEmpty || tags.toSet.subsetOf(m.tags.getOrElse(Map.empty).toSet))
//    }
//  }
//}

//adds kamon prometheus metrics reporter to be used/queried by main/test code
// and also it gets reset before each test
//@Deprecated
//trait ResetMetricsReporterBeforeEach
//  extends MetricsReadHelper
//    with BeforeAndAfterEach
//    with Matchers { this : Suite =>
//
//  override def beforeEach(): Unit = {
//    KamonPrometheusMetricsReporter._resetFixedMetricsReporter()
//    MetricsReader.getNodeMetrics().metrics.size shouldBe 0
//    super.beforeEach()
//  }
//}