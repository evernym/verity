package com.evernym.verity.actor.agent

import akka.actor.ActorRef
import com.evernym.verity.actor.metrics.{ActivityWindow, AgentActivity}
import com.evernym.verity.util.TimeUtil

trait AgentActivityTracker {
  def trackAgentActivity(msgType: String, domainId: String, sponsorId: Option[String]=None): Unit =
    activityTracker.foreach(at => at ! AgentActivity(domainId, TimeUtil.nowDateString, sponsorId.getOrElse(""), msgType))

  def setWindows(windows: ActivityWindow): Unit =
    activityTracker.foreach(at => at ! windows)

  lazy val activityTracker: Option[ActorRef]=None
}
