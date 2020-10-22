package com.evernym.verity.actor.cluster_singleton

import akka.actor.Props
import akka.cluster.sharding.ShardRegion.EntityId
import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor.persistence.BaseNonPersistentActor
import com.evernym.verity.config.AppConfig
import com.evernym.verity.metrics.MetricsWriter


object MetricsHelper {
  val name: String = METRICS_HELPER
  def props(appConfig: AppConfig) = Props(classOf[MetricsHelper], appConfig)
}

trait UpdateCountMetrics extends ActorMessageClass {
  def entityId: String
  def metricsName: String
  def count: Int
}

case class UpdateUndeliveredMsgCount(entityId: String, metricsName: String, count: Int) extends UpdateCountMetrics
case class UpdateFailedAttemptCount(entityId: String, metricsName: String, count: Int) extends UpdateCountMetrics
case class UpdateFailedMsgCount(entityId: String, metricsName: String, count: Int) extends UpdateCountMetrics

case class MetricsCountUpdated(metricsName: String, totalCount: Int) extends ActorMessageClass


/**
 * this is a non persistent actor (child of singleton actor)
 * needed to maintain total undelivered message count metrics across the cluster
 * this can be extended for any other such metrics
 * @param appConfig
 */
class MetricsHelper(implicit val appConfig: AppConfig) extends BaseNonPersistentActor {

  var undeliveredMsgCounts: Map[EntityId, Int] = Map.empty
  var failedAttemptCounts: Map[EntityId, Int] = Map.empty
  var failedMsgCounts: Map[EntityId, Int] = Map.empty

  override val receiveCmd: Receive = {
    case uumc: UpdateUndeliveredMsgCount =>
      undeliveredMsgCounts = undeliveredMsgCounts ++ Map(uumc.entityId -> uumc.count)
      val totalUndeliveredCount = undeliveredMsgCounts.values.sum
      updateMetrics(uumc.metricsName, totalUndeliveredCount)

    case ufac: UpdateFailedAttemptCount =>
      failedAttemptCounts = failedAttemptCounts ++ Map(ufac.entityId -> ufac.count)
      val totalFailedAttemptCount = failedAttemptCounts.values.sum
      updateMetrics(ufac.metricsName, totalFailedAttemptCount)

    case ufmc: UpdateFailedMsgCount =>
      failedMsgCounts = failedMsgCounts ++ Map(ufmc.entityId -> ufmc.count)
      val totalFailedMsgCount = failedMsgCounts.values.sum
      updateMetrics(ufmc.metricsName, totalFailedMsgCount)
  }

  def updateMetrics(metricsName: String, totalCount: Int): Unit = {
    MetricsWriter.gaugeApi.updateWithTags(metricsName, totalCount)
    sender ! MetricsCountUpdated(metricsName, totalCount)
  }

}
