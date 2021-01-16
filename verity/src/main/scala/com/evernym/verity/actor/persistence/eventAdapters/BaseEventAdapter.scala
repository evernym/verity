package com.evernym.verity.actor.persistence.eventAdapters

import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

trait BaseEventAdapter {
  val logger: Logger = getLoggerByClass(classOf[BaseEventAdapter])
  def convert(msg: Array[Byte]): Any
}
