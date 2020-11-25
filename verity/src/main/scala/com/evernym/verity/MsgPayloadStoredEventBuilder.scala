package com.evernym.verity

import com.evernym.verity.actor.agent.PayloadMetadata
import com.evernym.verity.actor.{MsgPayloadStored, PayloadContext}
import com.evernym.verity.protocol.engine.MsgId
import com.google.protobuf.ByteString


//this is kept here to be reused by code from within 'actor' and 'protocol' packages both.
//we should refactor this later on

object MsgPayloadStoredEventBuilder {

  def buildMsgPayloadStoredEvt(msgId: MsgId, message: Array[Byte], metadata: Option[PayloadMetadata]): MsgPayloadStored = {
    val context = metadata.map(md => PayloadContext(md.msgTypeStr, md.msgPackFormatStr))
    MsgPayloadStored(msgId, ByteString.copyFrom(message), context)
  }

}
