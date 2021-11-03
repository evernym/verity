package com.evernym.verity.util2

import com.evernym.verity.actor.agent.PayloadMetadata
import com.evernym.verity.actor.{MsgPayloadStored, PayloadContext}
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.google.protobuf.ByteString

object MsgPayloadStoredEventBuilder {

  def buildMsgPayloadStoredEvt(msgId: MsgId, message: Array[Byte], metadata: Option[PayloadMetadata]): MsgPayloadStored = {
    val context = metadata.map(md => PayloadContext(md.msgTypeStr, md.msgPackFormatStr))
    MsgPayloadStored(msgId, ByteString.copyFrom(message), context)
  }

}
