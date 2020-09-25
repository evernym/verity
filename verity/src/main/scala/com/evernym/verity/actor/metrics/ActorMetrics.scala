package com.evernym.verity.actor.metrics

import com.evernym.verity.actor.persistence.ActorCommon
import com.evernym.verity.metrics.MetricsWriter

/**
 * this is just a wrapper around metrics api for actors
 */

trait ActorMetrics { this: ActorCommon =>

  object ActorMetrics {
    def incrementGauge(name: String): Unit = {
      MetricsWriter.gaugeApi.increment(name)
    }
    def incrementGauge(name: String, value: Long): Unit = {
      MetricsWriter.gaugeApi.increment(name, value)
    }
  }
}
