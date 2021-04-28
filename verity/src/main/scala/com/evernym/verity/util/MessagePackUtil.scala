package com.evernym.verity.util

import com.evernym.verity.Exceptions._
import com.evernym.verity.agentmsg.DefaultMsgCodec

import java.math.BigInteger
import scala.collection.JavaConverters._


/**
 * utility methods to convert from/to a 'message packed' message
 */
object MessagePackUtil {

  def convertPackedMsgToJsonString(payload: Array[Byte]): String = {
    val map = MsgPackScalaUtil.unpack(payload)
    DefaultMsgCodec.toJson(map)
  }

  /**
   * string to 'message packed' message
   *
   * @param jsonString
   * @return
   */
  def convertJsonStringToPackedMsg(jsonString: String): Array[Byte] = {
    try {
      val map = DefaultMsgCodec.fromJson[Map[String, Any]](jsonString)
      MsgPackScalaUtil.pack(map)
    } catch {
      case e: IllegalArgumentException if e.getMessage.startsWith("requirement failed") =>
        throw new MissingReqFieldException(Option(s"required attribute not found (missing/empty/null): '$jsonString'"))
    }
  }

  /**
   * native msg to 'message packed' message
   *
   * @param msg
   * @tparam T
   * @return
   */
  def convertNativeMsgToPackedMsg[T](msg: T): Array[Byte] = {
    try {
      val json: String = DefaultMsgCodec.toJson(msg)
      convertJsonStringToPackedMsg(json)
    } catch {
      case e: IllegalArgumentException if e.getMessage.startsWith("requirement failed") =>
        throw new MissingReqFieldException(Option(s"required attribute not found (missing/empty/null): '$msg'"))
    }
  }
}



object MsgPackScalaUtil {

  import org.msgpack.core.{MessageBufferPacker, MessagePack}
  import org.msgpack.value.{Value, ValueType}

  /**
   * packs given map to 'message packed' data
   *
   * @param map map of key value pair
   * @return packed binary data
   */
  def pack(map: Map[String, Any]): Array[Byte] = {
    val packer = MessagePack.newDefaultBufferPacker()
    packer.packMapHeader(map.size)
    val baseTypePacker = packAnyType(packer)
    map.foreach { entry =>
      baseTypePacker(entry._1)
      baseTypePacker(entry._2)
    }
    packer.close()
    packer.toByteArray
  }

  /**
   * unpacks given 'message packed' data to map
   *
   * @param payload packed binary data
   * @return unpacked map
   */
  def unpack(payload: Array[Byte]): Map[String, Any] = {
    val unpacker = MessagePack.newDefaultUnpacker(payload)
    val unpacked = unpacker.unpackValue()
    unpacker.close()
    unpacked.asMapValue().map.asScala.map { case (key, value) =>
      key.asStringValue().toString -> unpackToAny(value)
    }.toMap
  }

  /**
   * packs given base type
   *
   * @param packer
   * @return
   */
  private def packAnyType(packer: MessageBufferPacker): PartialFunction[Any, Unit] = {
    case l: List[_]   =>
      packer.packArrayHeader(l.size)
      l.foreach(e => packAnyType(packer)(e))
    case m: Map[_,_] =>
      packer.packMapHeader(m.size)
      m.foreach { case (key, value) =>
        packAnyType(packer)(key)
        packAnyType(packer)(value)
      }
    case b: Byte          => packer.packByte(b)
    case s: Short         => packer.packShort(s)
    case l: Long          => packer.packLong(l)
    case f: Float         => packer.packFloat(f)
    case d: Double        => packer.packDouble(d)
    case b: Boolean       => packer.packBoolean(b)
    case s: String        => packer.packString(s)
    case i: Integer       => packer.packInt(i)
    case bi: BigInteger   => packer.packBigInteger(bi)
    case other            => throw new RuntimeException("type not supported during pack: " + other)
  }

  /**
   * unpacks given message pack 'value' to underlying base type
   * @param v value
   * @return Any
   */
  private def unpackToAny(v: Value): Any = {
    v.getValueType match {
      case ValueType.BOOLEAN  => v.asBooleanValue().getBoolean
      case ValueType.STRING   => v.asStringValue().asString()
      case ValueType.INTEGER  => v.asIntegerValue().asInt()
      case ValueType.FLOAT    => v.asFloatValue().toFloat
      case ValueType.ARRAY    => v.asArrayValue().asScala.toList.map(unpackToAny)
      case ValueType.BINARY   => v.asBinaryValue().asByteArray()
      case ValueType.MAP      => v.asMapValue()
                                  .entrySet()
                                  .asScala.map(e => unpackToAny(e.getKey) -> unpackToAny(e.getValue))
                                  .toMap
      case other              => throw new RuntimeException("type not supported during unpack: " + other)
    }
  }

}
