package com.evernym.verity.actor.base

import java.io.Serializable
import java.time.LocalDateTime

import akka.actor.ReceiveTimeout
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject}
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.MetricsWriter


/**
 * core actor extended with few common commands (including receive timeout handler)
 * and has actor timers for scheduled jobs
 */
trait CoreActorExtended extends CoreActor with HasActorTimers {

  def preReceiveTimeoutCheck(): Boolean = true

  def handleReceiveTimeout(): Unit = {
    genericLogger.debug(s"received ReceiveTimeout for ${self.path}")
    if (preReceiveTimeoutCheck()) {
      actorStopStartedOpt = Option(LocalDateTime.now)
      genericLogger.debug(s"[$actorId] actor wil start preparing for shutdown... ")
      stopActor()
      MetricsWriter.gaugeApi.increment(s"$AS_AKKA_ACTOR_TYPE_PREFIX.$entityName.$AS_AKKA_ACTOR_STOPPED_COUNT_SUFFIX")
    }
  }

  private def handleBaseCommand: Receive = {
    case s: Start           =>
      if (s.sendBackConfirmation) sender ! Done

    case s: Stop            =>
      if (s.sendBackConfirmation) sender ! Done
      stopActor()

    case ReceiveTimeout     => handleReceiveTimeout()
  }

  def baseCommandHandler(actualCmdReceiver: Receive): Receive =
    handleBaseCommand orElse
      coreCommandHandler(actualCmdReceiver)

  override def setNewReceiveBehaviour(receiver: Receive): Unit = {
    context.become(baseCommandHandler(receiver))
  }
}

abstract class SerializableObject extends Serializable with ActorMessageObject
case object Done extends SerializableObject
case object AlreadyDone extends SerializableObject
case object NotFound extends SerializableObject
case class Stop(sendBackConfirmation: Boolean = false) extends ActorMessageClass
case class Start(sendBackConfirmation: Boolean = false) extends ActorMessageClass
