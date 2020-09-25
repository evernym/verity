package com.evernym.verity

import java.time.format.DateTimeFormatterBuilder
import java.time.{Instant, ZoneId, ZonedDateTime}

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.{CoreConstants, LayoutBase}
import com.evernym.verity.util.OptionUtil

import scala.collection.JavaConverters._

/**
  * Custom Logging Layout to be used by logback.
  *
  * This custom layout allows for specific actions and transformations on the format of the log lines that might not be
  * possible with pattern formatting.
  *
  * @see [[https://logback.qos.ch/manual/layouts.html]]
  *
  * */
class LogLayout extends LayoutBase[ILoggingEvent] {
  import LogLayout._

  /**
    * Main function of the LogLayout that determines the final look of the log line.
    *
    * @param event forwarded log event that contains all of the necessary info
    * @return a formatted log line built from the info contained in the passed event
    *
    * Log lines are in the key="value" format:
    *   ts="timestamp" thread="thread" lvl="ERROR" logger="logger" src="file:line" msg="message"
    *
    * Additional arguments can be passed as separate kv pairs so they can be indexed later and used for searches
    *   ts="timestamp" thread="thread" lvl="ERROR" logger="logger" src="file:line" msg="message" key1="value1" key2="value2"
    *
    * == Example without arguments ==
    *
    * Call
    *   logger.error(
    *                 "could not send sms", --> message to be placed in 'msg' key
    *               )
    *
    * Output log line
    *   ts="2019-02-26T13:36:36.058Z[UTC]" thread="test-akka.actor.default-dispatcher-5" lvl="ERROR" logger="DefaultSMSSender" src="SMSServiceProvider.scala:78" msg="could not send sms"
    *
    * == Example with arguments ==
    * Call
    *   logger.error(
    *                 "could not send sms", --> message to be placed in 'msg' key
    *                 ("provider", "BW"),   --> argument that will be transformed into key="value"/provider="TW"
    *                 ("phone_number", "1234")
    *               )
    *
    * Output log line
    *   ts="2019-02-26T13:36:36.058Z[UTC]" thread="test-akka.actor.default-dispatcher-5" lvl="ERROR" logger="DefaultSMSSender" src="SMSServiceProvider.scala:78" msg="could not send sms" provider="BW" phone_number="1234"
    *
    * */
  def doLayout(event: ILoggingEvent): String = {
    // convert timestamp to a UTC zoned date time string
    val timestamp =
      dateTimeFormatter.format(ZonedDateTime.ofInstant(Instant.ofEpochMilli(event.getTimeStamp), ZoneId.of("UTC")))

    val threadName = safeGet(event.getThreadName)
    val level = safeGet(event.getLevel)
    val msg = escapeQuotes(safeGet(event.getFormattedMessage))
    val logger = safeGet(
      {
        safeGet(event.getLoggerName).split("\\.").lastOption.getOrElse("")
      }
    )
    val source = safeGet{
      Option(event.getCallerData).getOrElse(Array[StackTraceElement]()).headOption match {
        case Some(callerData) => callerData.getFileName + ":" + callerData.getLineNumber
        case _                => ""
      }
    }

    val args = safeGet{
      Option(event.getArgumentArray) match {
        case Some(arguments) =>
          arguments
            .map({
              case (arg1: String, arg2: Any) => tupleStr(arg1, arg2)
              case _                         => ""
            })
            .mkString(" ")
        case None => ""
      }
    }

    val mdc = safeGet {
      OptionUtil.emptyOption(event.getMDCPropertyMap)
        .map(_.asScala)
        .map {
          _.map { case (arg1, arg2) => tupleStr(arg1, arg2) }
        }
        .map(_.mkString("", " ", " "))
        .getOrElse("")
    }

    s"""$LOG_KEY_TIMESTAMP="$timestamp" $LOG_KEY_THREAD="$threadName" $LOG_KEY_LEVEL="$level" $LOG_KEY_LOGGER="$logger" $LOG_KEY_SOURCE="$source" $mdc$LOG_KEY_MESSAGE="$msg" $args${CoreConstants.LINE_SEPARATOR}"""
  }

}

object LogLayout {
  def escapeQuotes(arg: String) = arg.replace("\"", "'")
  def tupleStr(arg1: String, arg2: Any): String = {
    s"""$arg1="${escapeQuotes(arg2.toString)}""""
  }

  def safeGet(f: => Any): String = {
    try {
      Option(f).map(_.toString).getOrElse("")
    } catch { case _: NullPointerException => "" }
  }

  val LOG_KEY_TIMESTAMP = "ts"
  val LOG_KEY_THREAD = "thread"
  val LOG_KEY_LEVEL = "lvl"
  val LOG_KEY_LOGGER = "logger"
  val LOG_KEY_SOURCE = "src"
  val LOG_KEY_MESSAGE = "msg"

  val dateTimeFormatter = new DateTimeFormatterBuilder()
    .appendInstant(3)
    .appendLiteral('[')
    .appendZoneRegionId()
    .appendLiteral(']')
    .toFormatter()
}