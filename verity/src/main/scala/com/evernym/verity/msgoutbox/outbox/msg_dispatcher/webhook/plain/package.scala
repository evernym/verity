package com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook

import akka.actor.typed.Behavior
import com.evernym.verity.msgoutbox.outbox.msg_transporter.HttpTransporter

package object plain {
  case class MsgTransportParam(httpTransporter: Behavior[HttpTransporter.Cmd])
}
