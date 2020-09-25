package com.evernym.verity.apphealth

import info.faljse.SDNotify.SDNotify

trait SysServiceNotifier {
  def setStatus(newStatus: String): Unit
  def started(): Unit
  def stop(): Unit
}

object SDNotifySysServiceNotifier extends SysServiceNotifier {

  def setStatus(newStatus: String): Unit = {
    SDNotify.sendStatus(newStatus)
  }

  def started(): Unit = {
    SDNotify.sendNotify()
  }

  def stop(): Unit = {
    SDNotify.sendStopping()
  }
}
