package com.evernym.verity.observability.metrics

import com.evernym.verity.observability.metrics.backend.NoOpMetricsBackend

object NoOpMetricsWriter {
  def apply() = new MetricsWriter(new NoOpMetricsBackend)
}
