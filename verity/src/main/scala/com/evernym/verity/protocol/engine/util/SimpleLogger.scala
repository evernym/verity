package com.evernym.verity.protocol.engine.util

import com.typesafe.scalalogging.Logger
import org.slf4j.Marker

trait SimpleLoggerLike {
  def trace(msg: String): Unit
  def trace(msg: String, args: Any*): Unit
  def debug(msg: String): Unit
  def debug(msg: String, args: Any*): Unit
  def debug(marker: Marker, msg: String): Unit
  def info(msg: String): Unit
  def info(msg: String, args: Any*): Unit
  def warn(msg: String): Unit
  def warn(msg: String, args: Any*): Unit
  def error(msg: String): Unit
  def error(msg: String, exception: Throwable): Unit
  def error(msg: String, args: Any*): Unit

  def isDebugEnabled: Boolean
}

object SimpleLogger {
  def apply(clazz: Class[_]): SimpleLogger = new SimpleLogger(clazz.getName)
  def apply(name: String): SimpleLogger = new SimpleLogger(name)
}

class SimpleLogger(name: String) extends SimpleLoggerLike {

  def scalaLogger: Logger = {
    try {
      Logger(name)
    } catch {
      case e: Exception =>
        val errorMsg = s"unable to create logger with name '$name': " + getErrorMsg(e)
        throw new UnableToCreateLogger(errorMsg, e)
    }
  }

  override def trace(msg: String): Unit = scalaLogger.trace(msg)
  override def trace(msg: String, args: Any*): Unit = scalaLogger.trace(msg, args) // TODO: Should be args:_*?
  override def debug(msg: String): Unit = scalaLogger.debug(msg)
  override def debug(msg: String, args: Any*): Unit = scalaLogger.debug(msg, args)
  override def debug(marker: Marker, msg: String): Unit = scalaLogger.debug(marker, msg)
  override def info(msg: String): Unit = scalaLogger.info(msg)
  override def info(msg: String, args: Any*): Unit = scalaLogger.info(msg, args)
  override def warn(msg: String): Unit = scalaLogger.warn(msg)
  override def warn(msg: String, args: Any*): Unit = scalaLogger.warn(msg, args)
  override def error(msg: String): Unit = scalaLogger.error(msg)
  override def error(msg: String, exception: Throwable): Unit = scalaLogger.error(msg, exception)
  override def error(msg: String, args: Any*): Unit = scalaLogger.error(msg, args)

  override def isDebugEnabled: Boolean = scalaLogger.underlying.isDebugEnabled()
}

class UnableToCreateLogger(val msg: String, e: Throwable) extends RuntimeException(msg, e)
