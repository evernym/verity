package com.evernym.verity.actor.base

import java.io.Serializable

import akka.actor.{PoisonPill, ReceiveTimeout}
import com.evernym.verity.actor.ActorMessage

/**
 * this actor adds few common/base command handler (Ping, Stop, ReceiveTimeout) to 'CoreActor'
 * and has some utility methods for scheduled jobs
 */
trait CoreActorExtended extends CoreActor with HasActorTimers {

  final override def receive: Receive = extendedCoreCommandHandler(cmdHandler)

  final def extendedCoreCommandHandler(actualCmdReceiver: Receive): Receive =
    handleExtendedCmd orElse
      coreCommandHandler(actualCmdReceiver)

  private def handleExtendedCmd: Receive = {
    case p: Ping        =>
      if (p.sendAck) sender ! Done

    case s: Stop        =>
      stopActor()
      if (s.sendAck) sender ! Done

    case ReceiveTimeout => handleReceiveTimeout()
  }

  def preReceiveTimeoutCheck(): Boolean = true

  def handleReceiveTimeout(): Unit = {
    log.debug(s"[$actorId] processing ReceiveTimeout")
    if (preReceiveTimeoutCheck()) {
      log.debug(s"[$actorId] actor wil start preparing for shutdown... ")
      stopActor()
    }
  }

  def stopActor(): Unit = {
    context.self ! PoisonPill
  }

  override def setNewReceiveBehaviour(receiver: Receive, discardOld: Boolean = true): Unit = {
    context.become(extendedCoreCommandHandler(receiver), discardOld)
  }

}

abstract class SerializableObject extends Serializable with ActorMessage
case object Done extends SerializableObject
case object AlreadyDone extends SerializableObject
case object NotFound extends SerializableObject
case class Ping(sendAck: Boolean = false) extends ActorMessage
case class Stop(sendAck: Boolean = false) extends ActorMessage
