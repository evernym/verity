package com.evernym.verity.actor.testkit.checks

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.evernym.verity.testkit.BasicSpecBase
import org.scalatest.{AsyncTestSuite, Tag, TestSuite}

/** Mix in this trait to get automatic log checking in specs
  */
trait ChecksLogs extends ChecksForTestSuite {
  this: TestSuite with BasicSpecBase =>

  registerChecker(new LogChecker)
}

/** Mix in this trait to get automatic log checking in specs
  */
trait ChecksLogsAsync extends ChecksForAsyncTestSuite {
  this: AsyncTestSuite with BasicSpecBase =>

  registerChecker(new LogChecker)
}

/**
  * Core logic. Don't extend this. Don't use this
  * directly unless testing the actual trait itself.
  */
class LogChecker extends Checker {

  type Issue = ILoggingEvent
  type Context = TestAppender

  val ignoreTagNames: Set[String] = Set(IgnoreLog, UNSAFE_IgnoreLog).map(_.name)

  val patternsToCheck = List(
    EventPattern(Some(Level.ERROR), None, None),
    EventPattern(Some(Level.WARN), None, None)
  )

  def setup(): TestAppender = {
    val ta = new TestAppender
    if (ta.root.getLevel.levelInt > Level.WARN_INT)
      throw new CheckPreconditionException(s"test log level must be WARN or lower; it is currently set to: ${ta.root.getLevel}")
    ta
  }

  def check(setupObj: TestAppender): Unit = {
    patternsToCheck foreach checkNoEventsForPattern

    def checkNoEventsForPattern(epat: EventPattern): Unit = {
      val events = setupObj.findEvents(epat)
      if (events.nonEmpty) throw new FailedCheckException("Suspicious log events found", events)
    }
  }

  def teardown(setupObj: TestAppender): Unit = {
    setupObj.shutdown()
  }

}

object IgnoreLog extends Tag("IgnoreLog")

/**
 * This is a tag (a temporary one) to ignore failures happening due to enabling 'ChecksLogs' in 'BasicSpec'
 * And expectation is that we should fix the error and remove this tag one by one and eventually
 * we should remove this tag itself.
 */
object UNSAFE_IgnoreLog extends Tag("UNSAFE_IgnoreLog")