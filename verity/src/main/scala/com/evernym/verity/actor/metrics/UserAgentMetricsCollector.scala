package com.evernym.verity.actor.metrics

import akka.actor.{Actor, ActorSystem, Timers}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.metrics.UserAgentMetricsCollector.timerKey
import com.evernym.verity.metrics.{CustomMetrics, MetricsWriter}

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

class UserAgentMetricsCollector(implicit val actorSystem: ActorSystem) extends Actor with Timers {

  val stats: mutable.Map[String, Int] = mutable.Map.empty

  override def preStart(): Unit = {
    timers.startTimerWithFixedDelay(timerKey, WriteUserAgentMetrics(), 60.seconds) // todo interval
  }

  final override def receive: Receive = {
    case WriteUserAgentMetrics() => this.writeMetrics()
    case UpdateUserAgentMetric(id, value) => this.updateMetrics(id, value)
    case RemoveUserAgentMetric(id) => this.removeMetrics(id)
  }

  def writeMetrics(): Unit = {
    var max = 0
    var avg = 0
    if (stats.nonEmpty) {
      max = stats.values.max
      avg = stats.values.sum / stats.size // todo use double
    }
    MetricsWriter.gaugeApi.update(CustomMetrics.AS_USER_AGENT_REL_AGENTS_AVG, avg)
    MetricsWriter.gaugeApi.update(CustomMetrics.AS_USER_AGENT_REL_AGENTS_MAX, max)
  }

  def updateMetrics(id: String, value: Int): Unit = {
    stats.update(id, value)
  }

  def removeMetrics(id: String): Unit = {
    stats.remove(id)
  }

}

// todo could probably have generic payload
case class UpdateUserAgentMetric(agentId: String, value: Int) extends ActorMessage

case class RemoveUserAgentMetric(agentId: String) extends ActorMessage

case class WriteUserAgentMetrics() extends ActorMessage

object UserAgentMetricsCollector {
  private val timerKey = "UserAgentMetricsCollectorTimer"
}