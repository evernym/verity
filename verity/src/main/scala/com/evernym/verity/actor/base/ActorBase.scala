package com.evernym.verity.actor.base

import java.io.Serializable
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, ActorRef, PoisonPill, ReceiveTimeout}
import com.evernym.verity.actor.{ActorMessage, ActorMessageClass, ActorMessageObject, ExceptionHandler}
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.protocol.protocols.HasAppConfig
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.persistence.{ActorDetail, GetActorDetail}
import com.evernym.verity.metrics.MetricsWriter
import com.typesafe.scalalogging.Logger

/**
 * base actor for almost all actors (persistent or non-persistent) used in this codebase
 */
trait ActorBase extends HasActorMsgScheduler with HasAppConfig { this: Actor =>

  var actorStopStartedOpt: Option[LocalDateTime] = None

  def cmdSender: ActorRef

  // We have need of a super generic logging. But this is too high level to define a generic logger
  // So we have this private logger for those needs but should not be sub-classes
  private val genericLogger: Logger = LoggingUtil.getLoggerByName("ActorCommon")

  /**
   * incoming command handler to be implemented by super class
   *
   * @return
   */
  def cmdHandler: Receive

  def entityId: String = self.path.name

  def entityName: String = self.path.parent.name

  def actorId: String = entityId

  var totalPersistedEvents: Int = 0
  var totalRecoveredEvents: Int = 0

  def preReceiveTimeoutCheck(): Boolean = true

  final override def preStart(): Unit = {
    MetricsWriter.gaugeApi.increment(s"$AS_AKKA_ACTOR_TYPE_PREFIX.$entityName.$AS_AKKA_ACTOR_STARTED_COUNT_SUFFIX")
    preStartTime = LocalDateTime.now
    beforeStart()
  }

  final override def postStop(): Unit = {
    genericLogger.debug("in post stop: " + self.path)
    actorStopStartedOpt.foreach { actorStopStarted =>
      val actorStopped = LocalDateTime.now
      val stopTimeMillis = ChronoUnit.MILLIS.between(actorStopStarted, actorStopped)
      genericLogger.debug(s"[$actorId] stop-time-in-millis: $stopTimeMillis")
    }
  }

  def stopActor(): Unit = {
    context.self ! PoisonPill
  }

  def logCrashReason(reason: Throwable, message: Option[Any]): Unit = {
    genericLogger.error(s"[$actorId]: crashed and about to restart => " +
      s"message being processed while error happened: $message, " +
      s"reason: ${Exceptions.getStackTraceAsSingleLineString(reason)}")
  }

  def handleReceiveTimeout(): Unit = {
    genericLogger.debug(s"received ReceiveTimeout for ${self.path}")
    if (preReceiveTimeoutCheck()) {
      actorStopStartedOpt = Option(LocalDateTime.now)
      genericLogger.debug(s"[$actorId] actor wil start preparing for shutdown... ")
      stopActor()
      MetricsWriter.gaugeApi.increment(s"$AS_AKKA_ACTOR_TYPE_PREFIX.$entityName.$AS_AKKA_ACTOR_STOPPED_COUNT_SUFFIX")
    }
  }

  def postCommandExecution(cmd: Any): Unit = {
    //default implementation (do nothing)
  }

  def handleCommand(actualCmdReceiver: Receive): Receive = {
    case GetActorDetail     =>
      sender ! ActorDetail(actorId, totalPersistedEvents, totalRecoveredEvents)

    case s: Start           =>
      if (s.sendBackConfirmation) sender ! Done

    case s: Stop            =>
      if (s.sendBackConfirmation) sender ! Done
      stopActor()

    case ReceiveTimeout     => handleReceiveTimeout()

    case cmd: ActorMessage if actualCmdReceiver.isDefinedAt(cmd) =>
      try {
        actualCmdReceiver(cmd)
        postCommandExecution(cmd)
      } catch {
        case e: Exception =>
          handleException(e, cmdSender)
      }

    case cmd if actualCmdReceiver.isDefinedAt(cmd) =>
      //any incoming command should extend from 'ActorMessage' interface
      //to be able to serialize/deserialize
      throw new RuntimeException(s"[$actorId] incoming command not extending 'ActorMessage' interface: $cmd")
  }

  var preStartTime: LocalDateTime = _

  def beforeStart(): Unit = {
    //can be overridden by implementing class
  }

  def setNewReceiveBehaviour(receiver: Receive): Unit = {
    context.become(handleCommand(receiver))
  }

  def handleException(e: Throwable, sndr: ActorRef): Unit = {
    ExceptionHandler.handleException(e, sndr, Option(self))
  }
}

abstract class SerializableObject extends Serializable with ActorMessageObject
case object Done extends SerializableObject
case object NotFound extends SerializableObject
case object AlreadyDone extends SerializableObject
case class Stop(sendBackConfirmation: Boolean = false) extends ActorMessageClass
case class Start(sendBackConfirmation: Boolean = false) extends ActorMessageClass
