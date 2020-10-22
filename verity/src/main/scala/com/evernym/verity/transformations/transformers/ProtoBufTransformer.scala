package com.evernym.verity.transformations.transformers

import com.evernym.verity.actor.persistence.object_code_mapper.{DefaultObjectCodeMapper, ObjectCodeMapperBase}
import scalapb.GeneratedMessage

/**
 * serializes/deserializes scala PB generated message
 * @param objectCodeMapper a mapping between the object (scala PB generated message) and an unique code assigned to it
 */
class ProtoBufTransformer(objectCodeMapper: ObjectCodeMapperBase) extends (Any <=> Array[Byte]) {

  override val execute: Any => Array[Byte] = { msg =>
    val typeCode = objectCodeMapper.codeFromObject(msg)
    val msgBytes = msg.asInstanceOf[GeneratedMessage].toByteArray
    CodeMsgExtractor.pack(typeCode, msgBytes)
  }

  override val undo: Array[Byte] => Any = { msg =>
    val (typeCode, msgBytes) = CodeMsgExtractor.unpack(msg)
    objectCodeMapper.objectFromCode(typeCode, msgBytes)
  }

}

object DefaultProtoBufTransformer extends ProtoBufTransformer(DefaultObjectCodeMapper)

object CodeMsgExtractor {
  def pack(code: Int, msg: Array[Byte]): Array[Byte] = {
    val typeCodeBytes = BigInt(code).toByteArray
    //first byte will record length of bytes required to store 'type code'
    //TODO: will one byte always sufficient to store size of 'type code' bytes
    // CodeMsgExtractorSpec proves that it should work fine.
    typeCodeBytes.length.toByte +: (typeCodeBytes ++ msg)
  }

  def unpack(msg: Array[Byte]): (Int, Array[Byte]) = {
    val typeCodeBytes = msg.slice(1, 1 + msg.head)
    val typeCode = BigInt(typeCodeBytes).toInt
    val msgBytes = msg.takeRight(msg.length - (typeCodeBytes.length + 1))
    (typeCode, msgBytes)
  }
}