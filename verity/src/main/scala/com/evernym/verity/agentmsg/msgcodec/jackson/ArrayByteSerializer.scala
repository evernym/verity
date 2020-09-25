package com.evernym.verity.agentmsg.msgcodec.jackson

import com.fasterxml.jackson.core
import com.fasterxml.jackson.databind.{JsonSerializer, SerializerProvider}


class ArrayByteSerializer extends JsonSerializer[Array[Byte]] {
  override def serialize(value: Array[Byte], gen: core.JsonGenerator, serializers: SerializerProvider): Unit = {

    val intArray = new Array[Int](value.length)

    value.zipWithIndex.foreach { case (v, index) =>
      intArray(index) = v
    }
    gen.writeArray(intArray, 0, intArray.length)
  }
}
