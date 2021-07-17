package com.evernym.verity.msgoutbox.outbox

import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.msgoutbox.outbox.States.Initialized
import scalapb.GeneratedMessageCompanion

//should only contain state mapping for Outbox actor
object StateObjectMapper extends ObjectCodeMapperBase{

  override def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map(
    1 -> Initialized
  )
}
