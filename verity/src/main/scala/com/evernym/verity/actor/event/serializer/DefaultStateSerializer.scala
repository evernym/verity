package com.evernym.verity.actor.event.serializer

import com.evernym.verity.actor._
import com.evernym.verity.transformer.{EventDataTransformer, TransformedData}
import com.google.protobuf.ByteString
import scalapb.GeneratedMessage


trait StateSerializer {

  val stateMapper: EventCodeMapper

  def getTransformedState(state: State, encryptionKey: String): TransformedState = {
    val sebd = state.asInstanceOf[GeneratedMessage].toByteArray
    val ed = EventDataTransformer.pack(sebd, encryptionKey)
    val data = ByteString.copyFrom(ed.data)
    TransformedState(ed.transformationId, stateMapper.getCodeFromClass(state), data)
  }

  def getDeSerializedState(std: TransformedState, encryptionKey: String): Any = {
    val bed = std.data.toByteArray
    val dd = EventDataTransformer.unpack(TransformedData(bed, std.transformationId), encryptionKey)
    val sebd = dd.asInstanceOf[Array[Byte]]
    stateMapper.getClassFromCode(std.stateCode, sebd)
  }
}

object DefaultStateSerializer extends StateSerializer {
  val stateMapper: EventCodeMapper = DefaultStateMapper
}

object DefaultStateMapper extends EventCodeMapper {

  //NOTE: Never change the key (numbers) in below map once it is assigned and in use


  lazy val eventCodeMapping = Map (
    1 -> ResourceUsageState,
    2 -> ItemManagerState
  )

}
