package com.evernym.verity.msgoutbox.outbox.msg_transporter

import akka.actor.typed.Behavior

trait MsgTransports {
  def httpTransporter: Behavior[HttpTransporter.Cmd]
}
