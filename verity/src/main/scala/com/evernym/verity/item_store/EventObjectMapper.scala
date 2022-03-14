package com.evernym.verity.item_store

import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.item_store.Events._
import scalapb.GeneratedMessageCompanion

object EventObjectMapper
  extends ObjectCodeMapperBase{

  override def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map(
    1 -> ItemAdded,
    2 -> ItemRemoved
  )
}