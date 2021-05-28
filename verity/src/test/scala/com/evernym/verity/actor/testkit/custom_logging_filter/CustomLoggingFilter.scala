package com.evernym.verity.actor.testkit.custom_logging_filter

import akka.actor.ActorSystem
import akka.event.Logging._
import akka.event.{EventStream, LoggingFilterWithMarker}

/**
 * Supposed to be only used by individual specs where it has to test
 * log messages logged by akka LoggingAdapter without setting/changing
 * the logging backend's (logback etc) log level which may print some other
 * unnecessary logs and may clutter console
 *
 * see 'CustomLoggingFilterSpec' how it can be used
 *
 * @param settings
 * @param eventStream
 */
class CustomLoggingFilter(settings: ActorSystem.Settings, eventStream: EventStream)
  extends LoggingFilterWithMarker {

  override def isErrorEnabled(logClass: Class[_], logSource: String): Boolean =
    eventStream.logLevel >= ErrorLevel

  override def isWarningEnabled(logClass: Class[_], logSource: String): Boolean =
    eventStream.logLevel >= WarningLevel

  override def isInfoEnabled(logClass: Class[_], logSource: String): Boolean =
    eventStream.logLevel >= InfoLevel

  override def isDebugEnabled(logClass: Class[_], logSource: String): Boolean =
    eventStream.logLevel >= DebugLevel
}