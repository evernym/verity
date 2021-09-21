package com.evernym.verity.actor.agent

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.metrics.activity_tracker.{ActivityTrackingCommand, RecordAgentActivity}
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.constants.ActorNameConstants.ACTIVITY_TRACKER_REGION_ACTOR_NAME
import com.evernym.verity.observability.metrics.CustomMetrics.AS_NEW_USER_AGENT_COUNT
import com.evernym.verity.observability.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.DomainId
import com.evernym.verity.util.TimeUtil


trait HasAgentActivity {

  def system: ActorSystem
  def appConfig: AppConfig

  object AgentActivityTracker {

    lazy val activityTrackerRegionName: String = ACTIVITY_TRACKER_REGION_ACTOR_NAME
    lazy val activityTrackerRegion: ActorRef = ClusterSharding(system).shardRegion(activityTrackerRegionName)

    private def sendToRegion(id: DomainId, msg: ActivityTrackingCommand): Unit =
      activityTrackerRegion ! ForIdentifier(id, msg)

    def track(msgType: String,
              domainId: DomainId,
              relId: Option[String]=None,
              timestamp: String=TimeUtil.nowDateString) : Unit = {
      sendToRegion(
        domainId,
        RecordAgentActivity(domainId, timestamp, msgType, relId)
      )
    }

    def newAgent(sponsorRel: Option[SponsorRel], metricsWriter: MetricsWriter): Unit = {
      val tags = sponsorRel.map(s => ConfigUtil.getSponsorRelTag(appConfig, s)).getOrElse(Map())
      metricsWriter.gaugeIncrement(AS_NEW_USER_AGENT_COUNT, tags = tags)
    }
  }

}
