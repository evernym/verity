package com.evernym.verity.actor.msgoutbox.adapters

import akka.actor.typed.Behavior

trait Transports {
  def httpTransporter: Behavior[HttpTransporter.Cmd]
}
