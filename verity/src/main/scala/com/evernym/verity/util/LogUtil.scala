package com.evernym.verity.util

import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.Logger
import org.slf4j.event.Level
import Level._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

object LogUtil {
  def logDuration[T](logger: Logger,
                  actionDesc: String,
                  unit: TimeUnit = TimeUnit.MILLISECONDS,
                  level: Level = DEBUG)(action: => T): T = {
    val startTime = now()
    val rtn = action
    writeDuration(startTime, logger, actionDesc, unit, level)
    rtn
  }

  def logFutureDuration[T](logger: Logger,
                  actionDesc: String,
                  unit: TimeUnit = TimeUnit.MILLISECONDS,
                  level: Level = Level.DEBUG)
                  (action: => Future[T])
                  (implicit executor: ExecutionContext): Future[T] = {
    val startTime = now()
    action.map { x =>
      writeDuration(startTime, logger, actionDesc, unit, level)
      x
    }
  }

  private def writeDuration(startTime: FiniteDuration,
                            logger: Logger,
                            actionDesc: String,
                            unit: TimeUnit,
                            level: Level): Unit = {
    val duration = now() - startTime
    logAtLevel(logger, level)(s"$actionDesc: took ${duration.toUnit(unit)} (in ${unit.toString})")
  }

  private def now(): FiniteDuration = Duration(System.nanoTime(), TimeUnit.NANOSECONDS)

  def logAtLevel(logger: Logger, level: Level)(msg: => String): Unit = {
    level match {
      case DEBUG  => logger.debug(msg)
      case ERROR  => logger.error(msg)
      case INFO   =>  logger.info(msg)
      case WARN   => logger.warn(msg)
      case TRACE  => logger.trace(msg)
      case _      => //Ignore if it is some illegal level (logging is best effort)
    }
  }

}
