package com.evernym.verity.actor.event.serializer

import com.evernym.verity.config.AppConfig
import com.typesafe.config.ConfigException.Missing


object EventSerializerValidator {

  val configKey = "akka.actor.serialization-bindings"

  def validate(event: Any, appConfig: AppConfig): Unit = {
    //this will happen before every event which goes for persistence
    //so it is not very efficient/optimize
    //if this becomes bottleneck, then we may have to re-think it.
    val serializerBindings = appConfig.config.getConfig(configKey)
    val eventClassName = event.getClass.getName
    try {
      val serializer = serializerBindings.getString(s""""$eventClassName"""")
      if (serializer != "protoser") {
        throw new InvalidSerializerFound(s"invalid serializer '$serializer' found for event '$eventClassName'")
      }
    } catch {
      case m: Missing =>
        throw new NoSerializerFound(s"no serializer found for '$eventClassName'", m)
    }
  }
}

class InvalidSerializerFound(msg: String) extends RuntimeException(msg)
class NoSerializerFound(msg: String, e: Missing) extends RuntimeException(msg, e)
