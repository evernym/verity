package com.evernym.verity.actor.testkit.checks

import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory

class TestAppender(autostart: Boolean = true)  extends AppenderBase[ILoggingEvent] {

  private var _events: Vector[ILoggingEvent] = Vector.empty

  lazy val root: Logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]

  if (autostart) startup()

  def startup(): Unit = {
    setContext(root.getLoggerContext)
    start()
    root.addAppender(this)
  }

  def shutdown(): Unit = {
    root.detachAppender(this)
    stop()
  }

  def append(event: ILoggingEvent): Unit = _events = _events :+ event

  def getEvents: Vector[ILoggingEvent] = _events

  def findEvents(eventSignature: EventPattern): Vector[ILoggingEvent] = {
    _events.filter(eventMatch(eventSignature))
  }

  def eventMatch(esig: EventPattern)(event: ILoggingEvent): Boolean = {
    esig.loggerNameRegEx.forall(event.getLoggerName.matches) &&
      esig.message.forall(event.getMessage.matches) &&
      esig.minLogLevel.forall(event.getLevel.toInt >= _.toInt)
  }

}

case class EventPattern(minLogLevel: Option[Level], loggerNameRegEx: Option[String], message: Option[String])
