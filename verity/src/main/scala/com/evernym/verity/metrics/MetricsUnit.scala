package com.evernym.verity.metrics

sealed trait MetricsUnit { // todo rename? sounds like a metric system
  // todo do we really need this one?
}

object MetricsUnit {
  // TODO: add metric units
  object None extends MetricsUnit

  object Information {
    case object Bytes extends MetricsUnit

    case object Kilobytes extends MetricsUnit
  }
}
