package com.evernym.verity.actor.base

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.{Actor, ActorRef, PoisonPill}
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.{ActorMessage, ExceptionHandler}
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.metrics.CustomMetrics.{AS_AKKA_ACTOR_STARTED_COUNT_SUFFIX, AS_AKKA_ACTOR_TYPE_PREFIX}
import com.evernym.verity.metrics.MetricsWriter
import com.typesafe.scalalogging.Logger

/**
 * core actor for almost all actors (persistent or non-persistent) used in this codebase
 */
trait CoreActor extends Actor {

  var actorStopStartedOpt: Option[LocalDateTime] = None

  def cmdSender: ActorRef = sender()

  // We have need of a super generic logging. But this is too high level to define a generic logger
  // So we have this private logger for those needs but should not be sub-classes
  protected val genericLogger: Logger = LoggingUtil.getLoggerByName(getClass.getSimpleName)

  def entityId: String = self.path.name

  def entityName: String = self.path.parent.name

  def actorId: String = entityId

  final override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    logCrashReason(reason, message)
    super.preRestart(reason, message)
  }

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
    afterStop()
  }

  def stopActor(): Unit = {
    context.self ! PoisonPill
  }

  def logCrashReason(reason: Throwable, message: Option[Any]): Unit = {
    genericLogger.error(s"[$actorId]: crashed and about to restart => " +
      s"message being processed while error happened: $message, " +
      s"reason: ${Exceptions.getStackTraceAsSingleLineString(reason)}")
  }

  def postCommandExecution(cmd: Any): Unit = {
    //default implementation (do nothing)
  }

  private def handleCoreCommand(actualCmdReceiver: Receive): Receive = {
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

  /**
   * will be executed before actor starts as part of "preStart" actor lifecycle hook
   */
  def beforeStart(): Unit = {
    //can be overridden by implementing class
  }

  /**
   * will be executed after actor stops as part of "postStop" actor lifecycle hook
   */
  def afterStop(): Unit = {
    //can be overridden by implementing class
  }

  def coreCommandHandler(actualReceiver: Receive): Receive =
    handleCoreCommand(actualReceiver)

  def setNewReceiveBehaviour(receiver: Receive): Unit = {
    context.become(coreCommandHandler(receiver))
  }

  def handleException(e: Throwable, sndr: ActorRef): Unit = {
    ExceptionHandler.handleException(e, sndr, Option(self))
  }

  def receiveCmd: Receive
  def cmdHandler: Receive = receiveCmd
  override def receive: Receive = coreCommandHandler(cmdHandler)
}

