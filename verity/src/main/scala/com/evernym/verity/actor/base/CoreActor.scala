package com.evernym.verity.actor.base

import java.time.LocalDateTime
import akka.actor.{Actor, ActorLogging, ActorRef}
import com.evernym.verity.Exceptions
import com.evernym.verity.actor.appStateManager.{AppStateEvent, AppStateUpdateAPI}
import com.evernym.verity.constants.ActorNameConstants.DEFAULT_ENTITY_TYPE
import com.evernym.verity.actor.{ActorMessage, ExceptionHandler}
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.metrics.CustomMetrics.{AS_AKKA_ACTOR_RESTARTED_COUNT_SUFFIX, AS_AKKA_ACTOR_STARTED_COUNT_SUFFIX, AS_AKKA_ACTOR_STOPPED_COUNT_SUFFIX, AS_AKKA_ACTOR_TYPE_PREFIX}
import com.evernym.verity.metrics.{MetricsWriter, MetricsWriterExtension}
import com.typesafe.scalalogging.Logger

/**
 * this core actor is used for almost all actors in this codebase (persistent or non-persistent)
 * this is mostly to reuse start/stop metrics tracking and
 * generic incoming command validation (like if command extends 'ActorMessage' serializable interface or not etc)
 * and generic exception handling during command processing
 */
trait CoreActor
  extends Actor
    with EntityIdentifier
    with ActorLogging {

  val metricsWriter : MetricsWriter = MetricsWriterExtension(context.system).get()

  override def receive: Receive = coreCommandHandler(cmdHandler)

  final def coreCommandHandler(actualReceiver: Receive): Receive =
    handleCoreCommand(actualReceiver)

  private def handleCoreCommand(actualCmdReceiver: Receive): Receive = {
    case GetActorDetail     => sender ! actorDetail

    case cmd: ActorMessage if actualCmdReceiver.isDefinedAt(cmd) =>
      try {
        actualCmdReceiver(cmd)
        postCommandExecution(cmd)
      } catch {
        case e: Exception =>
          handleException(e, sender)
      }

    case cmd if sysCmdHandler.isDefinedAt(cmd) =>
      try {
        sysCmdHandler(cmd)
      } catch {
        case e: Exception =>
          handleException(e, sender)
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

  /**
   * override this to handle any akka system messages/commands/signals
   * @return
   */
  def sysCmdHandler: Receive = PartialFunction.empty

  final def cmdHandler: Receive = receiveCmd


  // We have need of a super generic logging. But this is too high level to define a generic logger
  // So we have this private logger for those needs but should not be sub-classes
  protected val genericLogger: Logger = LoggingUtil.getLoggerByName(getClass.getSimpleName)

  log.debug(s"[$actorId]: actor creation started")

  override def preStart(): Unit = {
    if (recordStartCountMetrics)
      metricsWriter.gaugeIncrement(
        s"$AS_AKKA_ACTOR_TYPE_PREFIX.$AS_AKKA_ACTOR_STARTED_COUNT_SUFFIX",
        tags = Map("entity-type"-> entityTypeTagName))
    log.debug(s"[$actorId]: in pre start")
    beforeStart()
    super.preStart()
  }

  override def postStop(): Unit = {
    if (recordStopCountMetrics)
      metricsWriter.gaugeIncrement(
        s"$AS_AKKA_ACTOR_TYPE_PREFIX.$AS_AKKA_ACTOR_STOPPED_COUNT_SUFFIX",
        tags = Map("entity-type"-> entityTypeTagName)
      )
    log.debug(s"[$actorId]: in post stop")
    afterStop()
    super.postStop()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    if (recordRestartCountMetrics)
      metricsWriter.gaugeIncrement(
        s"$AS_AKKA_ACTOR_TYPE_PREFIX.$AS_AKKA_ACTOR_RESTARTED_COUNT_SUFFIX",
        tags = Map("entity-type"-> entityTypeTagName)
      )
    log.debug(s"[$actorId]: in pre restart")
    logCrashReason(reason, message)
    super.preRestart(reason, message)
  }

  private def logCrashReason(reason: Throwable, message: Option[Any]): Unit = {
    log.error(s"[$actorId]: crashed and about to restart => " +
      message.map(m => s"message being processed while error happened: $m, ").getOrElse("") +
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

  def setNewReceiveBehaviour(receiver: Receive, discardOld: Boolean = true): Unit = {
    context.become(coreCommandHandler(receiver), discardOld)
  }

  def actorDetail: ActorDetail = ActorDetail(entityType, entityId, actorId)

  def entityTypeTagName: String = if (entityType == DEFAULT_ENTITY_TYPE) s"$entityType-$entityId" else entityType

  val recordStartCountMetrics = true
  val recordRestartCountMetrics = true
  val recordStopCountMetrics = true

  def publishAppStateEvent (event: AppStateEvent): Unit = {
    AppStateUpdateAPI(context.system).publishEvent(event)
  }

}

case object GetActorDetail extends ActorMessage
case class ActorDetail(entityType: String,
                       entityId: String,
                       actorId: String) extends ActorMessage
