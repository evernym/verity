package com.evernym.verity.actor.msgoutbox.outbox

import com.evernym.verity.actor.msgoutbox.outbox.Events.{MsgAdded, MsgRemoved, MsgSendingFailed, MsgSentSuccessfully, OutboxParamUpdated}
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import scalapb.GeneratedMessageCompanion

//should only contain event mapping for Message actor
object EventObjectMapper extends ObjectCodeMapperBase{

  override def objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map(
    1 -> OutboxParamUpdated,
    2 -> MsgAdded,
    3 -> MsgSentSuccessfully,
    4 -> MsgSendingFailed,
    5 -> MsgRemoved
  )
}
