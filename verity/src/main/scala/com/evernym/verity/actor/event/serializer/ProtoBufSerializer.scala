package com.evernym.verity.actor.event.serializer

import akka.serialization.SerializerWithStringManifest
import com.evernym.verity.actor._

class ProtoBufSerializer
  extends SerializerWithStringManifest {

  override def identifier: Int = 99

  //mostly we we'll have only one event (TransformedEvent) which will come at this level for serialization,
  // so if we want, we can just use empty string as manifest and it will work
  // but just in case if we have to support more events in future,
  // we can give numbers to different types of events.

  val TRANSFORMED_MULTI_EVENTS_MANIFEST = "0"
  val TRANSFORMED_EVENT_MANIFEST = "1"
  val TRANSFORMED_STATE_MANIFEST = "2"

  override def manifest(o: AnyRef): String = {
    o match {
      case _: TransformedMultiEvent => TRANSFORMED_MULTI_EVENTS_MANIFEST
      case _: TransformedEvent => TRANSFORMED_EVENT_MANIFEST
      case _: TransformedState => TRANSFORMED_STATE_MANIFEST
    }
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case te: TransformedEvent => te.toByteArray
      case te: TransformedMultiEvent => te.toByteArray
      case ts: TransformedState => ts.toByteArray
    }
  }

  def fromBinary(
                  bytes: Array[Byte],
                  manifest: String
                ): AnyRef = {
    manifest match {
      case TRANSFORMED_MULTI_EVENTS_MANIFEST => TransformedMultiEvent.parseFrom(bytes)
      case TRANSFORMED_EVENT_MANIFEST => TransformedEvent.parseFrom(bytes)
      case TRANSFORMED_STATE_MANIFEST => TransformedState.parseFrom(bytes)
    }
  }
}