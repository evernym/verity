package com.evernym.verity.actor.metrics

import akka.actor.{Actor, ActorSystem}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.observability.metrics.{CustomMetrics, MetricsWriter, MetricsWriterExtension}

import scala.collection.mutable

class CollectionsMetricCollector(implicit val actorSystem: ActorSystem) extends Actor {

  val metricsWriter: MetricsWriter = MetricsWriterExtension(actorSystem).get()

  // todo add metrics to self
  val size: mutable.Map[String, mutable.Map[String, Int]] = mutable.Map.empty
  var max: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)
  var sum: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)

  final override def receive: Receive = {
    case UpdateCollectionMetric(group, id, value) => this.updateMetrics(group, id, value)
    case RemoveCollectionMetric(group, id) => this.removeMetrics(group, id)
  }

  def updateMetrics(group: String, id: String, value: Int): Unit = {
    val map = size.getOrElseUpdate(group, mutable.Map.empty)
    map.put(id, value) match {
      case Some(oldValue) =>
        sum(group) += value - oldValue
        if (oldValue > value && oldValue == max(group)) max(group) = if (map.nonEmpty) map.values.max else 0
      case None =>
        sum(group) += value
    }
    if (max(group) < value) max(group) = value
    writeMetrics(group, max(group), map.size, sum(group))
  }

  def removeMetrics(group: String, id: String): Unit = {
    size.get(group).foreach { map =>
      map.remove(id).foreach { removedValue =>
        sum(group) -= removedValue
        if (removedValue == max(group)) {
          max(group) = if (map.nonEmpty) map.values.max else 0
        }
        writeMetrics(group, max(group), map.size, sum(group))
      }
    }
  }

  private def writeMetrics(group: String, max: Int, count: Int, sum: Int): Unit = {
    metricsWriter.gaugeUpdate(CustomMetrics.AS_COLLECTIONS_SUM, sum, Map("group" -> group))
    metricsWriter.gaugeUpdate(CustomMetrics.AS_COLLECTIONS_COUNT, count, Map("group" -> group))
    metricsWriter.gaugeUpdate(CustomMetrics.AS_COLLECTIONS_MAX, max, Map("group" -> group))
  }
}

case class UpdateCollectionMetric(groupId: String, collectionId: String, size: Int) extends ActorMessage

case class RemoveCollectionMetric(groupId: String, collectionId: String) extends ActorMessage

