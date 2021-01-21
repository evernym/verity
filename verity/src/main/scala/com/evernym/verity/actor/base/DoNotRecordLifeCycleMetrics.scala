package com.evernym.verity.actor.base

//mostly this will be used to not record metrics for those actors
// which can be created in very high numbers and may generate high cardinality metrics
// which is not recommended and also impacts performance
trait DoNotRecordLifeCycleMetrics { this: CoreActor =>
  override val recordStartCountMetrics = false
  override val recordRestartCountMetrics = false
  override val recordStopCountMetrics = false
}
