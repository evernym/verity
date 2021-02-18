package com.evernym.verity

import ch.qos.logback.classic.Level
import com.evernym.verity.testkit.agentmsg.AgentMsgHelper
import com.evernym.verity.testkit.mock.agent.{HasCloudAgent, MockAgent}

package object testkit {

  type Matchers = org.scalatest.matchers.should.Matchers

  def runWithLogLevel[T](lvl: Level)(block: => T): T = {

    import ch.qos.logback.classic.{Logger => LBLogger}
    import org.slf4j.LoggerFactory

    val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LBLogger]

    val savedLevel = rootLogger.getLevel
    rootLogger.setLevel(lvl)

    try block
    finally rootLogger.setLevel(savedLevel)
  }

  def runInAnotherThread(f: => Unit): Thread = {
    val thread = new Thread {
      override def run(): Unit = f
    }
    thread.start()
    thread.join()
    thread
  }

  def runInAnotherThread(timeout: Int)(f: => Unit): Thread = {
    val thread = new Thread {
      override def run(): Unit = f
    }
    thread.start()
    thread.join(timeout)
    thread
  }

  trait AgentWithMsgHelper extends MockAgent with HasCloudAgent with AgentMsgHelper with Matchers

}
