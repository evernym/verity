package com.evernym.verity.logging

import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.Marker

import scala.annotation.tailrec

/**
 * Filter for test logger to filter out exceptions that are thrown on purpose
 */
class PurposefulErrorsFilter extends TurboFilter {
  val searchStr = "purposefully throwing exception"
  @tailrec
  private def isAPurposefulThrowable(t: Throwable): Boolean = {
    if(t == null) false
    else {
      t.getMessage.contains(searchStr) ||
        isAPurposefulThrowable(t.getCause)
    }

  }

  override def decide(marker: Marker,
                      logger: Logger,
                      level: Level,
                      format: String,
                      params: Array[AnyRef],
                      t: Throwable): FilterReply = {
    if ( level == Level.ERROR || level == Level.WARN ) {
      if (Option(format).exists(_.contains(searchStr))) {
        FilterReply.DENY
      }
      else if (Option(t).exists(isAPurposefulThrowable)) {
        FilterReply.DENY
      }
      else {
        FilterReply.NEUTRAL
      }
    }
    else {
      FilterReply.NEUTRAL
    }
  }
}
