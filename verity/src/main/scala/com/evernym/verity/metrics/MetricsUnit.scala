package com.evernym.verity.metrics

sealed trait MetricsUnit

object MetricsUnit {
  object None extends MetricsUnit

  object Information {
    case object Bytes extends MetricsUnit

    case object Kilobytes extends MetricsUnit
  }

  object TimeUnit {
    case object Seconds extends MetricsUnit
  }
}
