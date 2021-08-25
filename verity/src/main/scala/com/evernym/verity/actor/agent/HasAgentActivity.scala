package com.evernym.verity.actor.agent

import com.evernym.verity.actor.{ForIdentifier, ShardRegionCommon}
import com.evernym.verity.actor.metrics.{ActivityTracking, ActivityWindow, AgentActivity}
import com.evernym.verity.config.ConfigUtil
import com.evernym.verity.observability.metrics.CustomMetrics.AS_NEW_USER_AGENT_COUNT
import com.evernym.verity.observability.metrics.MetricsWriter
import com.evernym.verity.protocol.engine.DomainId
import com.evernym.verity.util.TimeUtil


trait HasAgentActivity extends ShardRegionCommon {
  object AgentActivityTracker {

    private def sendToRegion(id: DomainId, msg: ActivityTracking): Unit =
      activityTrackerRegion ! ForIdentifier(id, msg)

    def track(msgType: String,
              domainId: DomainId,
              relId: Option[String]=None,
              timestamp: String=TimeUtil.nowDateString) : Unit = {
          sendToRegion(
            domainId,
            AgentActivity(domainId, timestamp, msgType, relId)
          )
    }

    def newAgent(sponsorRel: Option[SponsorRel], metricsWriter: MetricsWriter): Unit = {
      val tags = sponsorRel.map(s => ConfigUtil.getSponsorRelTag(appConfig, s)).getOrElse(Map())
      metricsWriter.gaugeIncrement(AS_NEW_USER_AGENT_COUNT, tags = tags)
    }

    def setWindows(domainId: DomainId, windows: ActivityWindow): Unit =
      sendToRegion(domainId, windows)
  }

}
