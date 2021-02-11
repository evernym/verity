package com.evernym.verity.testkit.mock.msgsendingsvc

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.testkit.actor.MockMsgSendingSvc
import com.evernym.verity.testkit.BasicSpecBase
import com.evernym.verity.actor.wallet.PackedMsg
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}


trait MockMsgSendingSvcListener {

  this: BasicSpecBase with Eventually =>

  def agentActorContext: AgentActorContext

  lazy val testMsgSendingSvc: MockMsgSendingSvc =
    agentActorContext.msgSendingSvc.asInstanceOf[MockMsgSendingSvc]

  def getTotalAgentMsgSentByCloudAgent: Int = testMsgSendingSvc.totalAgentMsgsSent
  def getTotalRestAgentMsgSentByCloudAgent: Int = testMsgSendingSvc.totalRestAgentMsgsSent

  def checkForNewMsg(currentMsgCount: Int): Option[PackedMsg] = {
    //this confirms that protocol does sent a message to registered endpoint
    eventually (timeout(Span(15, Seconds)), interval(Span(2, Seconds))) {
      getTotalAgentMsgSentByCloudAgent shouldBe currentMsgCount + 1
      val lastMsgOpt = testMsgSendingSvc.lastAgentMsgOption
      lastMsgOpt.isDefined shouldBe true
      lastMsgOpt.map(PackedMsg(_))
    }
  }

  def checkForNewRestMsg(currentMsgCount: Int): Option[String] = {
    //this confirms that protocol does sent a message to registered endpoint
    eventually (timeout(Span(15, Seconds)), interval(Span(2, Seconds))) {
      getTotalRestAgentMsgSentByCloudAgent shouldBe currentMsgCount + 1
      val lastMsgOpt = testMsgSendingSvc.lastAgentRestMsgOption
      lastMsgOpt.isDefined shouldBe true
      lastMsgOpt
    }
  }

  def withExpectNewMsgAtRegisteredEndpoint[T](f : => T): (T, Option[PackedMsg]) = {
    //this sleep is introduced to reduce/remove intermittent failure
    // around calculating 'currentReceivedMsgCount' correctly
    // after sufficient sleep, there is a less chance that any new message will be available/received
    // before executing supplied function 'f'
    Thread.sleep(3000)
    val currentReceivedMsgCount = getTotalAgentMsgSentByCloudAgent
    val result = f
    val msg = checkForNewMsg(currentReceivedMsgCount)
    (result, msg)
  }

  def withExpectNewRestMsgAtRegisteredEndpoint[T](f : => T): (T, Option[String]) = {
    //this sleep is introduced to reduce/remove intermittent failure
    // around calculating 'currentReceivedMsgCount' correctly
    // after sufficient sleep, there is a less chance that any new message will be available/received
    // before executing supplied function 'f'
    Thread.sleep(3000)
    val currentReceivedMsgCount = getTotalRestAgentMsgSentByCloudAgent
    val result = f
    val msg = checkForNewRestMsg(currentReceivedMsgCount)
    (result, msg)
  }
}