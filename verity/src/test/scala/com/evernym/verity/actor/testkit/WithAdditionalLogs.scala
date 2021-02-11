package com.evernym.verity.actor.testkit

import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import org.scalatest.{BeforeAndAfterAll, Suite, SuiteMixin}
import org.slf4j.{Logger, LoggerFactory}

/**
 * this is to be able to change log level for ActorLogging
 * mostly to be able to use EventFilter
 * NOTE: this requires "akka.loglevel" to be set to "DEBUG" in the configuration
 *
 */
trait WithAdditionalLogs
  extends SuiteMixin
    with BeforeAndAfterAll { this: Suite =>

  def toLevel = Level.DEBUG
  var initialLevel: Option[Level] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    initialLevel = Some(
      LoggerFactory
        .getLogger(Logger.ROOT_LOGGER_NAME)
        .asInstanceOf[LogbackLogger]
        .getLevel
    )
    LoggerFactory
      .getLogger(Logger.ROOT_LOGGER_NAME)
      .asInstanceOf[LogbackLogger]
      .setLevel(toLevel)
  }

  override def afterAll(): Unit = {
    super.afterAll()
    LoggerFactory
      .getLogger(Logger.ROOT_LOGGER_NAME)
      .asInstanceOf[LogbackLogger]
      .setLevel(initialLevel.get)
  }
}
