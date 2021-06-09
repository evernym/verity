package com.evernym.verity.testkit.mock.msgsendingsvc

import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.actor.testkit.actor.MockMsgSendingSvc
import com.evernym.verity.testkit.BasicSpecBase
import com.evernym.verity.actor.wallet.PackedMsg
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}


trait MockMsgSendingSvcListener {

  this: BasicSpecBase with Eventually =>

  def agentActorContext: AgentActorContext

  lazy val testMsgSendingSvc: MockMsgSendingSvc =
    agentActorContext.msgSendingSvc.asInstanceOf[MockMsgSendingSvc]

  def totalBinaryMsgSent: Int = testMsgSendingSvc.totalBinaryMsgsSent
  def totalRestMsgSent: Int = testMsgSendingSvc.totalRestAgentMsgsSent

  def checkForNewMsg(currentMsgCount: Int): Option[PackedMsg] = {
    //this confirms that protocol does sent a message to registered endpoint
    eventually (timeout(Span(15, Seconds)), interval(Span(200, Millis))) {
      totalBinaryMsgSent >= currentMsgCount + 1 shouldBe true
      val lastMsgOpt = testMsgSendingSvc.lastBinaryMsgSent
      lastMsgOpt.isDefined shouldBe true
      lastMsgOpt.map(PackedMsg(_))
    }
  }

  def checkForNewRestMsg(currentMsgCount: Int): Option[String] = {
    //this confirms that protocol does sent a message to registered endpoint
    eventually (timeout(Span(35, Seconds)), interval(Span(200, Millis))) {
      totalRestMsgSent >= currentMsgCount + 1 shouldBe true
      val lastMsgOpt = testMsgSendingSvc.lastRestMsgSent
      lastMsgOpt.isDefined shouldBe true
      lastMsgOpt
    }
  }

  def withExpectNewMsgAtRegisteredEndpoint[T](f : => T): (T, Option[PackedMsg]) = {
    //this sleep is introduced to reduce/remove intermittent failure
    // around calculating 'currentReceivedMsgCount' correctly
    // after sufficient sleep, there is a less chance that any new message will be available/received
    // before executing supplied function 'f'
    Thread.sleep(300)
    val currentReceivedMsgCount = totalBinaryMsgSent
    val result = f
    val msg = checkForNewMsg(currentReceivedMsgCount)
    (result, msg)
  }

  def withExpectNewRestMsgAtRegisteredEndpoint[T](f : => T): (T, Option[String]) = {
    //this sleep is introduced to reduce/remove intermittent failure
    // around calculating 'currentReceivedMsgCount' correctly
    // after sufficient sleep, there is a less chance that any new message will be available/received
    // before executing supplied function 'f'
    Thread.sleep(300)
    val currentReceivedMsgCount = totalRestMsgSent
    val result = f
    val msg = checkForNewRestMsg(currentReceivedMsgCount)
    (result, msg)
  }
}