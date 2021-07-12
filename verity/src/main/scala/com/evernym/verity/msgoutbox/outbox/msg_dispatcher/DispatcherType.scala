package com.evernym.verity.msgoutbox.outbox.msg_dispatcher

import com.evernym.verity.msgoutbox.MsgId
import com.evernym.verity.msgoutbox.outbox.States.MsgDeliveryAttempt

trait DispatcherType {
  def dispatch(msgId: MsgId, deliveryAttempts: Map[String, MsgDeliveryAttempt]): Unit
  def ack(msgId: MsgId): Unit
}
