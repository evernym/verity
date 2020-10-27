package com.evernym.verity.apphealth

import info.faljse.SDNotify.SDNotify

trait SysServiceNotifier {
  def setStatus(newStatus: String): Unit
  def started(): Unit
  def stop(): Unit
}

object SDNotifySysServiceNotifier extends SysServiceNotifier {
  private def shouldSend(): Boolean = sys.env
    .get("NOTIFY_SOCKET")
    .exists(!_.isEmpty)

  def setStatus(newStatus: String): Unit = {
    if(shouldSend()) {
      SDNotify.sendStatus(newStatus)
    }
  }

  def started(): Unit = {
    if(shouldSend()) {
      SDNotify.sendNotify()
    }
  }

  def stop(): Unit = {
    if(shouldSend()) {
      SDNotify.sendStopping()
    }
  }
}
