package com.evernym.verity.msgoutbox.outbox.msg_transporter

import akka.actor.typed.Behavior

trait Transports {
  def httpTransporter: Behavior[HttpTransporter.Cmd]
}
