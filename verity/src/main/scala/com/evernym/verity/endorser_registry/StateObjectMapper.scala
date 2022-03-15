package com.evernym.verity.endorser_registry

import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.endorser_registry.States.Initialized
import scalapb.GeneratedMessageCompanion

//should only contain state mapping for EndorserRegistry actor
object StateObjectMapper extends ObjectCodeMapperBase{
  override def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map(
    1 -> Initialized
  )
}
