package com.evernym.verity.actor.serializers

import akka.serialization.SerializerWithStringManifest
import com.evernym.verity.actor._

class ProtoBufSerializer
  extends SerializerWithStringManifest {

  override def identifier: Int = 99

  val PERSISTENT_MULTI_EVENT_MSG_MANIFEST = "0"
  val PERSISTENT_EVENT_MSG_MANIFEST = "1"
  val PERSISTENT_STATE_MSG_MANIFEST = "2"
  val PERSISTENT_MSG_MANIFEST = "3"

  override def manifest(o: AnyRef): String = {
    o match {
      case _: PersistentMultiEventMsg => PERSISTENT_MULTI_EVENT_MSG_MANIFEST
      case _: PersistentEventMsg      => PERSISTENT_EVENT_MSG_MANIFEST
      case _: PersistentStateMsg      => PERSISTENT_STATE_MSG_MANIFEST
      case _: PersistentMsg           => PERSISTENT_MSG_MANIFEST
    }
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case te: PersistentMultiEventMsg  => te.toByteArray
      case te: PersistentEventMsg       => te.toByteArray
      case ts: PersistentStateMsg       => ts.toByteArray
      case pd: PersistentMsg            => pd.toByteArray
    }
  }

  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case PERSISTENT_MULTI_EVENT_MSG_MANIFEST  => PersistentMultiEventMsg.parseFrom(bytes)
      case PERSISTENT_EVENT_MSG_MANIFEST        => PersistentEventMsg.parseFrom(bytes)
      case PERSISTENT_STATE_MSG_MANIFEST        => PersistentStateMsg.parseFrom(bytes)
      case PERSISTENT_MSG_MANIFEST              => PersistentMsg.parseFrom(bytes)
    }
  }
}