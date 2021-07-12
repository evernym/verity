package com.evernym.verity.msgoutbox.outbox.msg_packager

import akka.actor.typed.Behavior
import com.evernym.verity.msgoutbox.outbox.msg_packager.didcom_v1.DIDCommV1Packager

trait Packagers {
  def didCommV1Packager: Behavior[DIDCommV1Packager.Cmd]
}

