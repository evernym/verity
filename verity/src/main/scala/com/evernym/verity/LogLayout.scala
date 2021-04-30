package com.evernym.verity

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.CoreConstants.LINE_SEPARATOR
import ch.qos.logback.core.LayoutBase
import com.evernym.verity.util.OptionUtil

import java.lang.{Long => JavaLong}
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}
import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

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
    val timestamp = resolveDateTime(event)
    val threadName = safeGet(event.getThreadName)
    val level = safeGet(event.getLevel)
    val msg = cleanValueStr(safeGet(event.getFormattedMessage))
    val logger = resolveLogger(event)
    val source = resolveSource(event)
    val args = resolveArgs(event)
    val mdc = resolveMdc(event)

    s"""$LOG_KEY_LEVEL="$level" $LOG_KEY_TIMESTAMP="$timestamp" $LOG_KEY_THREAD="$threadName" $LOG_KEY_LOGGER="$logger" $LOG_KEY_SOURCE="$source" $mdc$LOG_KEY_MESSAGE="$msg" $args$LINE_SEPARATOR"""
  }

}

/**
 * A more human read (specially for consoles) log layout
 */
class DevLogLayout extends LayoutBase[ILoggingEvent] {

  import LogLayout._

  val pattern = "[%highlight(%-5le)] [%magenta(%d{HH:mm:ss.SSS})] [%yellow(%10.15t)] [%cyan(%lo{25}:%M:%L)] -- %msg"

  private lazy val patternedLayout = {
    val layout = new PatternLayout()
    layout.setPattern(pattern)
    layout.setContext(this.getContext)
    layout.start()
    layout
  }

  override def doLayout(event: ILoggingEvent): String = {
    val args = resolveArgs(event)

    val argsFormatted = if (args.trim.nonEmpty) {
      s"[$args]"
    } else ""

    val log = removeNewLines(patternedLayout.doLayout(event))
    s"$log $argsFormatted$LINE_SEPARATOR"
  }
}

object LogLayout {
  def resolveDateTime(event: ILoggingEvent): String = {
    dateTimeFormatter.format(
      ZonedDateTime.ofInstant(
        Instant.ofEpochMilli(event.getTimeStamp),
        ZoneId.of("UTC")
      )
    )
  }

  def resolveSource(event: ILoggingEvent): String = {
    safeGet{
      Option(event.getCallerData).getOrElse(Array[StackTraceElement]()).headOption match {
        case Some(callerData) => callerData.getFileName + ":" + callerData.getLineNumber
        case _                => ""
      }
    }
  }

  def resolveArgs(event: ILoggingEvent): String = {
    safeGet{
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
  }

  def convertKamonId(id: String) = {
    Try{
//      BigInt(id, 16).toString() alternative implementation but it is much slower
      JavaLong.toUnsignedString(
        JavaLong.parseUnsignedLong(id, 16)
      )
    }.getOrElse(id)
  }

  def resolveMdc(event:ILoggingEvent): String = {
    safeGet {
      val mdc = OptionUtil.emptyOption(event.getMDCPropertyMap)
        .map(_.asScala)
        .getOrElse(mutable.Map.empty)
        .map {
          case ("kamonSpanId", id)  => tupleStr("kamonSpanId", convertKamonId(id))
          case ("kamonTraceId", id) => tupleStr("kamonTraceId", convertKamonId(id))
          case (arg1, arg2) => tupleStr(arg1, arg2)
        }
      if(mdc.isEmpty) ""
      else mdc.mkString("", " ", " ")
    }
  }

  def resolveLogger(event: ILoggingEvent): String = {
    safeGet(
      {
        safeGet(event.getLoggerName).split("\\.").lastOption.getOrElse("")
      }
    )
  }

  def cleanValueStr(arg: String): String = removeNewLines(arg)
    .replace("\"", "'")

  def removeNewLines(arg: String): String = arg
    .replace("\r", "")
    .replace("\n", "")

  def tupleStr(arg1: String, arg2: Any): String = {
    if (arg1 == null || arg2 == null) ""
    else s"""$arg1="${cleanValueStr(arg2.toString)}""""
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

  val dateTimeFormatter: DateTimeFormatter = new DateTimeFormatterBuilder()
    .appendInstant(3)
    .appendLiteral('[')
    .appendZoneRegionId()
    .appendLiteral(']')
    .toFormatter()
}