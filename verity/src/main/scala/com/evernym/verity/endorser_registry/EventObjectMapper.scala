package com.evernym.verity.endorser_registry

import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import scalapb.GeneratedMessageCompanion

//should only contain event mapping for EndorserRegistry actor
object EventObjectMapper extends ObjectCodeMapperBase{
  override def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map(
    1 -> Events.EndorserAdded,
    2 -> Events.EndorserRemoved
  )
}
