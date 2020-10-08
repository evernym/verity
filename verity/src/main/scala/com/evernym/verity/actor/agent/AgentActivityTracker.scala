package com.evernym.verity.actor.agent

import com.evernym.verity.actor.agent.agency.SponsorRel
import com.evernym.verity.actor.{ForIdentifier, ShardRegionCommon}
import com.evernym.verity.actor.metrics.{ActivityTracking, ActivityWindow, AgentActivity}
import com.evernym.verity.metrics.CustomMetrics.AS_NEW_USER_AGENT_COUNT
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.util.TimeUtil
import com.evernym.verity.util.OptionUtil.optionToEmptyStr

trait AgentActivityTracker extends ShardRegionCommon {
  private def sendToRegion(id: String, msg: ActivityTracking): Unit =
    activityTrackerRegion ! ForIdentifier(id, msg)

  def trackAgentActivity(msgType: String,
                         domainId: String,
                         sponsorId: Option[String],
                         sponseeId: Option[String],
                         relId: Option[String]): Unit =
    sendToRegion(
      domainId,
      AgentActivity(domainId, TimeUtil.nowDateString, SponsorRel(sponsorId, sponseeId), msgType, relId)
    )

  def trackNewAgent(sponsorId: Option[String]): Unit =
    MetricsWriter.gaugeApi.incrementWithTags(AS_NEW_USER_AGENT_COUNT, Map("sponsorId" -> sponsorId))

  def setWindows(domainId: String, windows: ActivityWindow): Unit =
    sendToRegion(domainId, windows)
}
