package com.evernym.verity.actor.base

import java.io.Serializable

import akka.actor.ReceiveTimeout
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject}


/**
 * core actor extended with few common commands (including receive timeout handler)
 * and has actor timers for scheduled jobs
 */
trait CoreActorExtended extends CoreActor with HasActorTimers {

  override def receive: Receive = baseCommandHandler(cmdHandler)

  final def baseCommandHandler(actualCmdReceiver: Receive): Receive =
    handleBaseCommand orElse
      coreCommandHandler(actualCmdReceiver)

  private def handleBaseCommand: Receive = {
    case s: Ping        =>
      if (s.sendBackConfirmation) sender ! Done

    case s: Stop        =>
      if (s.sendBackConfirmation) sender ! Done
      stopActor()

    case ReceiveTimeout => handleReceiveTimeout()
  }

  def preReceiveTimeoutCheck(): Boolean = true

  def handleReceiveTimeout(): Unit = {
    genericLogger.debug(s"received ReceiveTimeout for ${self.path}")
    if (preReceiveTimeoutCheck()) {
      genericLogger.debug(s"[$actorId] actor wil start preparing for shutdown... ")
      stopActor()
    }
  }

  override def setNewReceiveBehaviour(receiver: Receive): Unit = {
    context.become(baseCommandHandler(receiver))
  }

}

abstract class SerializableObject extends Serializable with ActorMessageObject
case object Done extends SerializableObject
case object AlreadyDone extends SerializableObject
case object NotFound extends SerializableObject
case class Ping(sendBackConfirmation: Boolean = false) extends ActorMessageClass
case class Stop(sendBackConfirmation: Boolean = false) extends ActorMessageClass
