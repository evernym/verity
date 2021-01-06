package com.evernym.verity.actor.serializers

import akka.serialization.SerializerWithStringManifest
import com.evernym.verity.actor._

class ProtoBufSerializer
  extends SerializerWithStringManifest {

  override def identifier: Int = 99

  //NOTE: don't change below given manifest numbers as that won't be backward compatible and
  // deserialization will fail for previously serialized events
  val DEPRECATED_MULTI_EVENT_MSG_MANIFEST = "0"
  val DEPRECATED_EVENT_MSG_MANIFEST = "1"
  val DEPRECATED_STATE_MSG_MANIFEST = "2"

  val PERSISTENT_MSG_MANIFEST = "3"
  val PERSISTENT_MULTI_EVENT_MSG_MANIFEST = "4"

  override def manifest(o: AnyRef): String = {
    o match {
      case _: DeprecatedEventMsg      => DEPRECATED_EVENT_MSG_MANIFEST
      case _: DeprecatedStateMsg      => DEPRECATED_STATE_MSG_MANIFEST
      case _: DeprecatedMultiEventMsg => DEPRECATED_MULTI_EVENT_MSG_MANIFEST

      case _: PersistentMsg           => PERSISTENT_MSG_MANIFEST
      case _: PersistentMultiEventMsg => PERSISTENT_MULTI_EVENT_MSG_MANIFEST
    }
  }

  override def toBinary(o: AnyRef): Array[Byte] = {
    o match {
      case dem: DeprecatedEventMsg        => dem.toByteArray
      case dsm: DeprecatedStateMsg        => dsm.toByteArray
      case dmem: DeprecatedMultiEventMsg  => dmem.toByteArray

      case pm: PersistentMsg              => pm.toByteArray
      case pmem: PersistentMultiEventMsg  => pmem.toByteArray
    }
  }

  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case DEPRECATED_EVENT_MSG_MANIFEST        => DeprecatedEventMsg.parseFrom(bytes)
      case DEPRECATED_STATE_MSG_MANIFEST        => DeprecatedStateMsg.parseFrom(bytes)
      case DEPRECATED_MULTI_EVENT_MSG_MANIFEST  => DeprecatedMultiEventMsg.parseFrom(bytes)

      case PERSISTENT_MSG_MANIFEST              => PersistentMsg.parseFrom(bytes)
      case PERSISTENT_MULTI_EVENT_MSG_MANIFEST  => PersistentMultiEventMsg.parseFrom(bytes)
    }
  }
}