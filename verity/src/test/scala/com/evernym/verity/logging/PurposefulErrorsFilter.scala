package com.evernym.verity.logging

import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

/**
 * Filter for test logger to filter out exceptions that are thrown on purpose
 */
class PurposefulErrorsFilter extends TurboFilter{
  override def decide(marker: Marker,
                      logger: Logger,
                      level: Level,
                      format: String,
                      params: Array[AnyRef],
                      t: Throwable): FilterReply = {
    val hasMsg= Option(t)
      .exists(
        _.getMessage.contains("purposefully throwing exception")
      )
    val isErrorOrWarning = level == Level.ERROR || level == Level.WARN

    if(hasMsg && isErrorOrWarning) {
      FilterReply.DENY
    }
    else FilterReply.NEUTRAL
  }
}
