package com.evernym.verity.actor.msgoutbox.message

import com.evernym.verity.actor.msgoutbox.message.Events._
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import scalapb.GeneratedMessageCompanion

//should only contain event mapping for Message actor
object EventObjectMapper extends ObjectCodeMapperBase{

  override def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map(
    1 -> MsgAdded,
    2 -> DeliveryAttemptRecorded,
    3 -> PayloadDeleted
  )
}
