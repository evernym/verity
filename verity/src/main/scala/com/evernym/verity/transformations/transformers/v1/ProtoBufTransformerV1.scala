package com.evernym.verity.transformations.transformers.v1

import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.transformations.transformers.{<=>, ObjectBuilder}
import scalapb.GeneratedMessage

/**
 * serializes/deserializes scala PB generated message
 * @param objectCodeMapper a mapping between the object (scala PB generated message)
 *                         and an unique code assigned to it
 */
class ProtoBufTransformerV1(objectCodeMapper: ObjectCodeMapperBase)
  extends (Any <=> Array[Byte]) {

  override val execute: Any => Array[Byte] = { msg =>
    val typeCode = objectCodeMapper.codeFromObject(msg)
    val msgBytes = msg.asInstanceOf[GeneratedMessage].toByteArray
    CodeMsgExtractorV1.pack(typeCode, msgBytes)
  }

  override val undo: Array[Byte] => Any = { msg =>
    val (typeCode, msgBytes) = CodeMsgExtractorV1.unpack(msg)
    ObjectBuilder.create(typeCode, msgBytes, objectCodeMapper)
  }

}

/**
 * for 'DefaultProtoBufTransformer', to be able to persist any event/snapshot,
 * there are two things to be saved
 *  a) event/state code (which we store in 'DefaultObjectCodeMapper')
 *  b) the event/state itself (the proto buf message)
 *
 *  This CodeMsgExtractorV1 utility methods are to be able to pack and unpack such data.
 */
object CodeMsgExtractorV1 {

  /**
   * packs (combines) the code and the msg and returns final array bytes
   * |------------------------------------------------------------------------------|
   * | first_byte            | byte code                 | message                  |
   * |------------------------------------------------------------------------------|
   * |<length of byte code>  | 37 (binary example code)  | binary proto buf message |
   * |------------------------------------------------------------------------------|
   *
   * @param code entity/state type code
   * @param msg entity/state proto buf message
   * @return
   */
  def pack(code: Int, msg: Array[Byte]): Array[Byte] = {
    val typeCodeBytes = BigInt(code).toByteArray
    //first byte will record length of bytes required to store 'type code'
    //TODO: will one byte always sufficient to store size of 'type code' bytes
    // CodeMsgExtractorV1Spec proves that it should work fine.
    typeCodeBytes.length.toByte +: (typeCodeBytes ++ msg)
  }

  /**
   * unpacks (extracts) the code and the msg from array bytes
   * @param packedMsg binary message (output of above pack function)
   * @return
   */
  def unpack(packedMsg: Array[Byte]): (Int, Array[Byte]) = {
    val typeCodeBytes = packedMsg.slice(1, 1 + packedMsg.head)
    val typeCode = BigInt(typeCodeBytes).toInt
    val msgBytes = packedMsg.takeRight(packedMsg.length - (typeCodeBytes.length + 1))
    (typeCode, msgBytes)
  }
}