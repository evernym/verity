package com.evernym.verity.actor.persistence

import com.evernym.verity.config.AppConfig
import com.typesafe.config.ConfigException.Missing


object PersistenceSerializerValidator {

  val configKey = "akka.actor.serialization-bindings"

  //TODO: This check was useful when java serializer was used by default when no specific serializer is found.
  // After moving to akka 2.6, java serializer is not enabled by default (confirm this)
  // and so not sure if we need to keep doing this validation.
  /**
   *  makes sure whatever gets persistence is a proto buf message and
   * accidentally it doesn't use any default serializer (like java serializer etc)
   *
   * @param msg message to be persisted
   * @param appConfig application config
   */
  def validate(msg: Any, appConfig: AppConfig): Unit = {
    //this will happen before every event/state which goes for persistence
    //so it is not very efficient/optimize
    //if this becomes bottleneck, then we may have to re-think it.
    val serializerBindings = appConfig.config.getConfig(configKey)

    val msgClassName = msg.getClass.getName
    try {
      val serializer = serializerBindings.getString(s""""$msgClassName"""")
      if (serializer != "protoser") {
        throw new InvalidSerializerFound(s"invalid serializer '$serializer' found for message '$msgClassName'")
      }
    } catch {
      case m: Missing =>
        throw new NoSerializerFound(s"no serializer found for '$msgClassName'", m)
    }
  }
}

class InvalidSerializerFound(msg: String) extends RuntimeException(msg)
class NoSerializerFound(msg: String, e: Missing) extends RuntimeException(msg, e)
