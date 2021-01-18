package com.evernym.verity.actor.base

trait DoNotRecordLifeCycleMetrics { this: CoreActor =>
  override val recordStartCountMetrics = false
  override val recordRestartCountMetrics = false
  override val recordStopCountMetrics = false
}
