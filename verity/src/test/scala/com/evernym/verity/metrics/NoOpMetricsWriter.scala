package com.evernym.verity.metrics

import com.evernym.verity.metrics.backend.NoOpMetricsBackend

object NoOpMetricsWriter {
  def apply() = new MetricsWriter(new NoOpMetricsBackend)
}
