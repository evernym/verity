package com.evernym.verity.actor.base

import java.time.LocalDateTime

import akka.actor.{Actor, ActorRef}
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.{ActorMessage, ExceptionHandler}
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.metrics.CustomMetrics.{AS_AKKA_ACTOR_STARTED_COUNT_SUFFIX, AS_AKKA_ACTOR_STOPPED_COUNT_SUFFIX, AS_AKKA_ACTOR_TYPE_PREFIX}
import com.evernym.verity.metrics.MetricsWriter
import com.typesafe.scalalogging.Logger

/**
 * this core actor is used for almost all actors in this codebase (persistent or non-persistent)
 * this is mostly to reuse start/stop metrics tracking and
 * generic incoming command validation (like if command extends 'ActorMessage' serializable interface or not etc)
 * and generic exception handling during command processing
 */
trait CoreActor extends Actor {

  override def receive: Receive = coreCommandHandler(cmdHandler)

  final def coreCommandHandler(actualReceiver: Receive): Receive =
    handleCoreCommand(actualReceiver)

  private def handleCoreCommand(actualCmdReceiver: Receive): Receive = {
    case cmd: ActorMessage if actualCmdReceiver.isDefinedAt(cmd) =>
      try {
        actualCmdReceiver(cmd)
        postCommandExecution(cmd)
      } catch {
        case e: Exception => handleException(e, sender())
      }

    case cmd if actualCmdReceiver.isDefinedAt(cmd) =>
      //any incoming command should extend from 'ActorMessage' interface
      //to be able to serialize/deserialize
      throw new RuntimeException(s"[$actorId] incoming command not extending 'ActorMessage' interface: $cmd")
  }

  /**
   * to be supplied by implemented class
   * @return
   */
  def receiveCmd: Receive

  final def cmdHandler: Receive = receiveCmd

  // We have need of a super generic logging. But this is too high level to define a generic logger
  // So we have this private logger for those needs but should not be sub-classes
  protected val genericLogger: Logger = LoggingUtil.getLoggerByName(getClass.getSimpleName)

  lazy val isShardedActor: Boolean = self.path.toString.contains("sharding")
  lazy val isClusterSingletonChild: Boolean = self.path.toString.contains("cluster-singleton-mngr")

  lazy val entityId: String = self.path.name
  lazy val entityName: String =
    if (isShardedActor || isClusterSingletonChild) self.path.parent.parent.name
    else entityId
  lazy val actorId: String = if (entityName != entityId) entityName + "-" + entityId else entityId

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    logCrashReason(reason, message)
    super.preRestart(reason, message)
  }

  override def preStart(): Unit = {
    MetricsWriter.gaugeApi.increment(s"$AS_AKKA_ACTOR_TYPE_PREFIX.$entityName.$AS_AKKA_ACTOR_STARTED_COUNT_SUFFIX")
    beforeStart()
  }

  override def postStop(): Unit = {
    genericLogger.debug("in post stop: " + self.path)
    afterStop()
    MetricsWriter.gaugeApi.increment(s"$AS_AKKA_ACTOR_TYPE_PREFIX.$entityName.$AS_AKKA_ACTOR_STOPPED_COUNT_SUFFIX")
  }

  private def logCrashReason(reason: Throwable, message: Option[Any]): Unit = {
    genericLogger.error(s"[$actorId]: crashed and about to restart => " +
      message.map(m => s"message being processed while error happened: " + m).getOrElse("") +
      s"reason: ${Exceptions.getStackTraceAsSingleLineString(reason)}")
  }

  def postCommandExecution(cmd: Any): Unit = {
    //default implementation (do nothing)
  }

  var preStartTime: LocalDateTime = LocalDateTime.now

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

  final def handleException(e: Throwable, sndr: ActorRef): Unit = {
    ExceptionHandler.handleException(e, sndr, Option(self))
  }

  def setNewReceiveBehaviour(receiver: Receive): Unit = {
    context.become(coreCommandHandler(receiver))
  }
}

