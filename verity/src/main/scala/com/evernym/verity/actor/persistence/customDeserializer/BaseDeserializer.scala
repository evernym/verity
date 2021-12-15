package com.evernym.verity.actor.persistence.customDeserializer

import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.typesafe.scalalogging.Logger

/**
 * this is used during event/state deserialization process to handle specific
 * failure and convert them to latest event/state object.
 */
trait BaseDeserializer {
  val logger: Logger = getLoggerByClass(classOf[BaseDeserializer])
  def deserialize(msg: Array[Byte]): Any
}
