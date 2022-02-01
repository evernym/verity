package com.evernym.verity.item_store

import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.item_store.States.Ready
import scalapb.GeneratedMessageCompanion

object StateObjectMapper
  extends ObjectCodeMapperBase {

  override def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map(
    1 -> Ready
  )
}
