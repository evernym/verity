package com.evernym.verity.logging

import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicSpec
import org.slf4j.event.Level

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

class ThrottledLoggerSpec extends BasicSpec with CommonSpecUtil {

  lazy val testAppConfig: AppConfig = new TestAppConfig()
  override def appConfig: AppConfig = testAppConfig

  class ThrottledLoggerWithMocks extends ThrottledLoggerBase[Int](5.minutes, 4) {
    var currentTimestamp: Long = 0
    def advanceTime(value: FiniteDuration): Unit = {
      currentTimestamp += value.toSeconds
    }

    val messages: ArrayBuffer[String] = ArrayBuffer[String]()

    def now: Long = currentTimestamp

    def doLog(level: Level, message: String): Unit = {
      messages.append(message)
    }
  }

  "ThrottledLoggerSpec" - {

    "logger doesn't try to log anything by itself" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.messages shouldBe List()
    }

    "logger logs first message as is" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.messages shouldBe List("some message")
    }

    "logger doesn't log messages with repetitive id" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.info(1, "some message")
      logger.messages shouldBe List("some message")
    }

    "logger doesn't log messages with repetitive id before min_period time passed" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period / 2)
      logger.info(1, "some message")
      logger.messages shouldBe List("some message")
    }

    "logger does log messages with repetitive id after enough time passed" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period * 3 / 2)
      logger.info(1, "some message")
      logger.messages shouldBe List(
        "some message",
        "some message"
      )
    }

    "logger doesn't log different messages with repetitive id" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.info(1, "other message")
      logger.messages shouldBe List("some message")
    }

    "logger does log repetitive messages with different id" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.info(2, "some message")
      logger.messages shouldBe List(
        "some message",
        "some message"
      )
    }

    "logger does log different messages with different ids" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.info(2, "other message")
      logger.messages shouldBe List(
        "some message",
        "other message"
      )
    }

    "logger doesn't log messages with different levels but repetitive ids" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.warn(1, "some warning")
      logger.messages shouldBe List("some message")
    }

    "logger notifies about skipped messages when logging same message after enough time passed" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period * 2 / 3)
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period * 2 / 3)
      logger.info(1, "some message")
      logger.messages shouldBe List(
        "some message",
        s"some message and got 1 more similar message(s) in last ${logger.min_period * 4 / 3}"
      )
    }

    "logger always logs current message" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period * 2 / 3)
      logger.info(1, "other message")
      logger.advanceTime(logger.min_period * 2 / 3)
      logger.info(1, "last message")
      logger.messages shouldBe List(
        "some message",
        s"last message and got 1 more similar message(s) in last ${logger.min_period * 4 / 3}"
      )
    }

    // TODO: Should we fix that???
    "logger doesn't notify about skipped message when logging different message even after enough time passed" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period * 2 / 3)
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period * 2 / 3)
      logger.info(2, "other message")
      logger.messages shouldBe List(
        "some message",
        "other message"
      )
    }

    "logger resets timer when it logs message" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period * 3 / 2)
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period * 2 / 3)
      logger.info(1, "some message")
      logger.advanceTime(logger.min_period * 2 / 3)
      logger.info(1, "some message")
      logger.messages shouldBe List(
        "some message",
        "some message",
        s"some message and got 1 more similar message(s) in last ${logger.min_period * 4 / 3}"
      )
    }

    "logger doesn't reset timer when other message is logged" in {
      val logger = new ThrottledLoggerWithMocks()
      logger.info(1, "some message")
      logger.info(2, "other message")
      logger.info(1, "some message")
      logger.messages shouldBe List(
        "some message",
        "other message"
      )
    }

    "logger has limit on how many messages it can track" in {
      val logger = new ThrottledLoggerWithMocks()
      val messages = (0 until logger.capacity + 2).map(i => (i, s"message $i")) :+ (0, "message 0")
      messages.foreach {
        case (id, message) => logger.info(id, message)
      }
      logger.messages shouldBe messages.map {
        case (id, message) => message
      }
    }
  }
}
