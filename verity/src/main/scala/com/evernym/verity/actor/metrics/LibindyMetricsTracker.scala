package com.evernym.verity.actor.metrics

import akka.actor.Actor
import org.hyperledger.indy.sdk.metrics.Metrics

case class LibindyMetricsTick()

class LibindyMetricsTracker extends Actor {
  def collectLibindyMetrics(): Unit = {
    val metricsResult = Metrics.collectMetrics.get
    println(metricsResult)
//    MetricsWriter.gaugeApi.updateWithTags(metricsName, totalCount)
  }


  def receive = {
    case LibindyMetricsTick() => this.collectLibindyMetrics()
  }

}