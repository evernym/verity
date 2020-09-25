package com.evernym.verity.actor.persistence

import akka.actor.{Actor, ActorRef}

trait BaseNonPersistentActor
  extends Actor
    with ActorCommon {

  override def cmdSender: ActorRef = sender()
  def receiveCmd: Receive
  final override def cmdHandler: Receive = receiveCmd
  final override def receive: Receive = handleCommand(cmdHandler)

  final override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    logCrashReason(reason, message)
    super.preRestart(reason, message)
  }
}