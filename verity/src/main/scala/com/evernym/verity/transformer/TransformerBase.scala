package com.evernym.verity.transformer


case class TransformedData(data: Array[Byte], transformationId: Int = 0)

trait TransformerBase {
  def pack(data: Any, metaData: Any): TransformedData
  def unpack(transformedData: TransformedData, metaData: Any): Any
}
