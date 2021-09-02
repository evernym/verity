package com.evernym.verity.util

import com.evernym.verity.did.didcomm.v1.messages.MsgId

import java.util.UUID
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.{Control, CtlEnvelope}

object MsgUtil {

  def newMsgId: MsgId = UUID.randomUUID.toString

  def newThreadId: MsgId = UUID.randomUUID.toString

  /**
    * encloses a control message in a Control Envelope (CtlEnvelope)
    */
  def encloseCtl(ctl: Control): CtlEnvelope[Control] = {
    val mid = newMsgId
    CtlEnvelope(ctl, mid, mid)
  }

  def encloseCtl(ctl: Control, threadId: ThreadId): CtlEnvelope[Control] = {
    CtlEnvelope(ctl, newMsgId, threadId)
  }

}
