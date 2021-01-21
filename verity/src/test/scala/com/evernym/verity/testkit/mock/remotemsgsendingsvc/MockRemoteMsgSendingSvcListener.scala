package com.evernym.verity.testkit.mock.remotemsgsendingsvc

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.testkit.actor.MockRemoteMsgSendingSvc
import com.evernym.verity.testkit.BasicSpecBase
import com.evernym.verity.actor.wallet.PackedMsg
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}


trait MockRemoteMsgSendingSvcListener {

  this: BasicSpecBase with Eventually =>

  def agentActorContext: AgentActorContext

  lazy val testRemoteMsgSendingSvc: MockRemoteMsgSendingSvc =
    agentActorContext.remoteMsgSendingSvc.asInstanceOf[MockRemoteMsgSendingSvc]

  def getTotalAgentMsgSentByCloudAgent: Int = testRemoteMsgSendingSvc.totalAgentMsgsSent
  def getTotalRestAgentMsgSentByCloudAgent: Int = testRemoteMsgSendingSvc.totalRestAgentMsgsSent

  def checkForNewMsg(currentMsgCount: Int): Option[PackedMsg] = {
    //this confirms that protocol does sent a message to registered endpoint
    eventually (timeout(Span(15, Seconds)), interval(Span(2, Seconds))) {
      getTotalAgentMsgSentByCloudAgent shouldBe currentMsgCount + 1
      val lastMsgOpt = testRemoteMsgSendingSvc.lastAgentMsgOption
      lastMsgOpt.isDefined shouldBe true
      lastMsgOpt.map(PackedMsg(_))
    }
  }

  def checkForNewRestMsg(currentMsgCount: Int): Option[String] = {
    //this confirms that protocol does sent a message to registered endpoint
    eventually (timeout(Span(15, Seconds)), interval(Span(2, Seconds))) {
      getTotalRestAgentMsgSentByCloudAgent shouldBe currentMsgCount + 1
      val lastMsgOpt = testRemoteMsgSendingSvc.lastAgentRestMsgOption
      lastMsgOpt.isDefined shouldBe true
      lastMsgOpt
    }
  }

  def withExpectNewMsgAtRegisteredEndpoint[T](f : => T): (T, Option[PackedMsg]) = {
    val currentReceivedMsgCount = getTotalAgentMsgSentByCloudAgent
    val result = f
    val msg = checkForNewMsg(currentReceivedMsgCount)
    (result, msg)
  }

  def withExpectNewRestMsgAtRegisteredEndpoint[T](f : => T): (T, Option[String]) = {
    val currentReceivedMsgCount = getTotalRestAgentMsgSentByCloudAgent
    val result = f
    val msg = checkForNewRestMsg(currentReceivedMsgCount)
    (result, msg)
  }
}