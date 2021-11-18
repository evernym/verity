package com.evernym.verity.metrics.backend

import akka.actor.ActorSystem
import com.evernym.verity.observability.metrics.backend.{LightbendTelemetryMetricsBackend => Backend}

class LightbendTelemetryMetricsBackend(system: ActorSystem) extends Backend(system)