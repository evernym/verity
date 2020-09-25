package com.evernym.verity.actor.testkit.checks.specs

import ch.qos.logback.classic
import com.evernym.verity.actor.testkit.checks.TestAppender
import com.evernym.verity.testkit.BasicFixtureSpec
import com.typesafe.scalalogging
import org.scalatest.Outcome

class TestAppenderSpec extends BasicFixtureSpec {

  type FixtureParam = TestAppender

  override def withFixture(test: OneArgTest): Outcome = {
    val appender = new TestAppender
    appender.startup()

    try {
      withFixture(test.toNoArgTest(appender))
    }
    finally {
      appender.shutdown()
    }
  }

  "A Test Appender" - {
    "when configured properly" - {
      "should capture log events" in { appender =>

      val root: classic.Logger = appender.root

        root.error("THIS IS A FAKE LOG MESSAGE FOR TESTING")

        appender.getEvents.size shouldBe 1
        appender.getEvents.head.toString shouldBe "[ERROR] THIS IS A FAKE LOG MESSAGE FOR TESTING"
      }
      "should capture log events for new unknown ScalaLogging logger" in { appender =>

        val logger: scalalogging.Logger = scalalogging.Logger("some random logger")
        logger.error("THIS IS A FAKE LOG MESSAGE FOR TESTING")

        appender.getEvents.size shouldBe 1
        appender.getEvents.head.toString shouldBe "[ERROR] THIS IS A FAKE LOG MESSAGE FOR TESTING"
      }
    }
  }
}
