package com.evernym.verity.actor.agent

import com.evernym.verity.Main.agentMsgRouter.activityTrackerRegion
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.metrics.{ActivityTracking, ActivityWindow, AgentActivity}
import com.evernym.verity.util.TimeUtil

trait AgentActivityTracker {
  private def sendToRegion(id: String, msg: ActivityTracking): Unit =
    activityTrackerRegion ! ForIdentifier(id, msg)

  def trackAgentActivity(msgType: String, domainId: String, sponsorId: Option[String]=None, relId: Option[String]): Unit =
    sendToRegion(domainId, AgentActivity(domainId, TimeUtil.nowDateString, sponsorId.getOrElse(""), msgType, relId))

  def setWindows(domainId: String, windows: ActivityWindow): Unit =
    sendToRegion(domainId, windows)
}
