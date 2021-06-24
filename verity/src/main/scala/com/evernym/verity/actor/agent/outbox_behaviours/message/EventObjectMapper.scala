package com.evernym.verity.actor.agent.outbox_behaviours.message

import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import scalapb.GeneratedMessageCompanion

object EventObjectMapper extends ObjectCodeMapperBase{

  override def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map(
    1 -> Added
  )
}
