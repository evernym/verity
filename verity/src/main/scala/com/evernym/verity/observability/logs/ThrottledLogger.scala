package com.evernym.verity.observability.logs

import com.evernym.verity.util.{LogUtil, Util}
import com.typesafe.scalalogging.Logger
import org.slf4j.event.Level

import scala.concurrent.duration._

abstract class ThrottledLoggerBase[ID](val min_period: FiniteDuration,
                                       val capacity: Int) {
  def trace(id: ID, message: String): Unit = {
    write(Level.TRACE, id, message)
  }

  def debug(id: ID, message: String): Unit = {
    write(Level.DEBUG, id, message)
  }

  def info(id: ID, message: String): Unit = {
    write(Level.INFO, id, message)
  }

  def warn(id: ID, message: String): Unit = {
    write(Level.WARN, id, message)
  }

  def error(id: ID, message: String): Unit = {
    write(Level.ERROR, id, message)
  }

  protected def now: Long
  protected def doLog(level: Level, str: String): Unit

  case class Metadata(lastLogged: Long, unloggedCount: Int = 0)
  private val cache = Util.makeCache[ID, Metadata](capacity)

  private def write(level: Level, id: ID, message: String): Unit = {
    Option(cache.get(id)) match {
      case None =>
        rawLog(level, id, message)

      case Some(meta) =>
        val period = (now - meta.lastLogged).seconds
        if (period < min_period) {
          cache.put(id, Metadata(meta.lastLogged, meta.unloggedCount + 1))
        } else {
          if (meta.unloggedCount > 0) {
            rawLog(level, id, s"$message and got ${meta.unloggedCount} more similar message(s) in last $period")
          } else {
            rawLog(level, id, message)
          }
        }
    }
  }

  private def rawLog(level: Level, id: ID, message: String): Unit = {
    doLog(level, message)
    cache.put(id, Metadata(now))
  }
}


class ThrottledLogger[ID](logger: Logger,
                          min_period: FiniteDuration = 5.minutes,
                          capacity: Int = 1024)
  extends ThrottledLoggerBase[ID](min_period, capacity) {

  def now: Long = System.nanoTime() / 1000000000

  def doLog(level: Level, text: String): Unit = {
    LogUtil.logAtLevel(logger, level)(text)
  }
}
