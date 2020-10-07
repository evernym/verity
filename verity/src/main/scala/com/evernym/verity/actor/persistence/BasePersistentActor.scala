package com.evernym.verity.actor.persistence

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.{ActorRef, Kill, Stash}
import akka.event.LoggingReceive
import akka.persistence._
import akka.util.Timeout
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions._
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.UNSUPPORTED_MSG_TYPE
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.actor.event.serializer.{DefaultEventSerializer, EventSerializer, EventSerializerValidator}
import com.evernym.verity.apphealth.AppStateConstants._
import com.evernym.verity.apphealth.{AppStateManager, ErrorEventParam, SeriousSystemError}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.protocol.engine.MultiEvent
import com.evernym.verity.util.Util._
import com.typesafe.config.ConfigException
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.Exceptions

import scala.concurrent.Future
import scala.concurrent.duration._


/**
 * base class for almost all persistent actors used in this codebase
 */

trait BasePersistentActor
  extends PersistentActor
    with EventPersistenceEncryption
    with ActorCommon
    with HasActorResponseTimeout
    with DeleteMsgCallbackHandler
    with Stash {

  def incrementTotalPersistedEvent(by: Int = 1): Unit = {
    totalPersistedEvents = totalPersistedEvents + by
  }

  def incrementTotalRecoveredEvent(by: Int = 1): Unit = {
    totalRecoveredEvents = totalRecoveredEvents + by
  }

  val eventSerializer: EventSerializer = DefaultEventSerializer

  val defaultReceiveTimeoutInSeconds = 600
  val entityCategory: String = PERSISTENT_ACTOR_BASE
  override lazy val entityName: String = self.path.parent.parent.name

  override lazy val actorId: String = entityName + "-" + entityId
  override lazy val persistenceId: String = actorId

  override def cmdSender: ActorRef = sender()

  def emptyEventHandler(event: Any): Unit = {}

  def applyEvent(evt: Any): Unit = receiveRecover(evt)

  def postPersist(): Unit = {}

  def postPersistAsync(): Unit = {}

  final override def persist[A](event: A)(handler: A => Unit): Unit = {
    val exception = new RuntimeException("call 'persistExt' instead")
    logger.error(Exceptions.getStackTraceAsSingleLineString(exception))
    throw exception
  }

  final override def persistAsync[A](event: A)(handler: A => Unit): Unit = {
    val exception = new RuntimeException("call 'persistAsyncExt' instead")
    logger.error(Exceptions.getStackTraceAsSingleLineString(exception))
    throw exception
  }

  def buildTransformedEvents(evt: Any): Any = {
    def getTransformedEvent(evt: Any): TransformedEvent = eventSerializer.getTransformedEvent(evt, persistenceEncryptionKey)
    try {
      evt match {
        case x: MultiEvent   => TransformedMultiEvent(x.evts.map(e => getTransformedEvent(e)))
        case e               => getTransformedEvent(e)
      }
    } catch {
      case e: Exception =>
        logger.error(Exceptions.getErrorMsg(e))
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        throw e
    }
  }

  def buildEventDetail(evt: Any): String = {
    evt.toString.split("\n").filterNot(_.isEmpty).mkString(", ")
  }

  def trackPersistenceFailure(): Unit = {
    val duration = System.currentTimeMillis() - persistStart
    ActorMetrics.incrementGauge(AS_SERVICE_DYNAMODB_PERSIST_FAILED_COUNT)
    ActorMetrics.incrementGauge(AS_SERVICE_DYNAMODB_PERSIST_DURATION, duration)
  }

  def trackPersistenceSuccess(): Unit = {
    val duration = System.currentTimeMillis() - persistStart
    ActorMetrics.incrementGauge(AS_SERVICE_DYNAMODB_PERSIST_SUCCEED_COUNT)
    ActorMetrics.incrementGauge(AS_SERVICE_DYNAMODB_PERSIST_DURATION, duration)
  }

  final def persistExtCommon(event: Any, sync: Boolean)(handler: Any => Unit): Unit = {
    persistStart = System.currentTimeMillis()
    val successHandler = handler andThen { _ =>
      val eventDetail = buildEventDetail(event)
      logger.trace(eventDetail + " event persisted", ("event", event.getClass.getSimpleName),
        (LOG_KEY_PERSISTENCE_ID, persistenceId))
      trackPersistenceSuccess()
      if (sync) {
        postPersist()
      } else {
        postPersistAsync()
      }
      AppStateManager.recoverIfNeeded(CONTEXT_EVENT_PERSIST)
    }

    incrementTotalPersistedEvent()

    try {
      event match {
        case evt: Any =>
          val te = buildTransformedEvents(evt)
          EventSerializerValidator.validate(te, appConfig)
          if (sync) {
            super.persist(te)(successHandler)
          } else {
            super.persistAsync(te)(successHandler)
          }
      }
    } catch {
      case e: Exception =>
        val errorMsg = s"error during persisting actor event ${event.getClass.getSimpleName}: ${Exceptions.getErrorMsg(e)}"
        trackPersistenceFailure()
        handlePersistenceFailure(e, errorMsg)
    }
  }

  final def persistExt(event: Any)(handler: Any => Unit): Unit = {
    persistExtCommon(event, sync = true)(handler)
  }

  final def persistAsyncExt(event: Any)(handler: Any => Unit): Unit = {
    persistExtCommon(event, sync = false)(handler)
  }

  def writeWithoutApply(evt: Any): Unit = persistExt(evt)(emptyEventHandler)

  def asyncWriteWithoutApply(evt: Any): Unit = persistAsyncExt(evt)(emptyEventHandler)

  def writeAndApply(evt: Any): Unit = {
    runWithInternalSpan("writeAndApply", "BasePersistentActor") {
      persistExt(evt)(receiveRecover)
    }
  }

  def asyncWriteAndApply(evt: Any): Unit= {
    runWithInternalSpan("asyncWriteAndApply", "BasePersistentActor") {
      asyncWriteWithoutApply(evt)
      applyEvent(evt)
    }
  }

  /**
   * writes/persists events and then applies the event (state change)
   * and then that event is sent back to the command/message sender
   * @param evt
   */
  def writeApplyAndSendItBack(evt: Any): Unit = {
    writeAndApply(evt)
    sender ! evt
  }

  def asyncWriteApplyAndSendItBack(evt: Any): Unit = {
    asyncWriteAndApply(evt)
    sender ! evt
  }

  var persistStart: Long = _

  val defaultWarnRecoveryTimeInMilliseconds: Int = 1000

  lazy val warnRecoveryTime: Int = appConfig.getConfigIntOption(PERSISTENT_PROTOCOL_WARN_RECOVERY_TIME_MILLISECONDS)
    .getOrElse(defaultWarnRecoveryTimeInMilliseconds)

  def safeGetAppConfigIntOption(key: String): Option[Int] = {
    try{
      appConfig.getConfigIntOption(key)
    }
    catch {
      case e: ConfigException =>
        logger.warn(s"Exception during getting key: $key from config: $e")
        None
    }
  }

  override def beforeStart(): Unit = {
    logger.debug("in pre-start", (LOG_KEY_PERSISTENCE_ID, persistenceId))
    context.become(receiveActorInitHandler)
  }

  def entityCategoryForReceiveTimeout: String = {
    entityCategory.replace("$", "")
  }
  def entityNameForReceiveTimeout: String = {
    //if entityName == "/", it is non sharded actor
    if (entityName == "/") getClass.getSimpleName.replace("$", "")
    else entityName.replace("$", "")
  }
  def entityIdForReceiveTimeout: String = {
    entityId.replace("$", "")
  }

  def entityReceiveTimeout: Duration = PassivateUtil.getReceiveTimeout(appConfig, defaultReceiveTimeoutInSeconds,
    entityCategoryForReceiveTimeout, entityNameForReceiveTimeout, entityIdForReceiveTimeout)

  /*
  Called after being reconstituted from event-sourced material.
   */
  def postRecoveryCompleted(): Unit = {
    runWithInternalSpan("postRecoveryCompleted", "BasePersistentActor") {
      context.setReceiveTimeout(entityReceiveTimeout)
      val startTime = LocalDateTime.now
      logger.debug("post actor recovery started", (LOG_KEY_PERSISTENCE_ID, persistenceId))
      val futures = postActorRecoveryCompleted()
      Future.sequence(futures).map { _ =>
        val curTime = LocalDateTime.now
        val millis = ChronoUnit.MILLIS.between(startTime, curTime)
        logger.debug(s"post actor recovery finished, time taken (in millis): $millis", (LOG_KEY_PERSISTENCE_ID, persistenceId))
        self ! ActorInitializedPostRecovery
      }.recover {
        case e: Exception =>
          self ! ActorInitPostRecoveryFailed(e)
      }
    }
  }

  def postActorRecoveryCompleted(): List[Future[Any]] = {
    List.empty
  }

  var isSuccessfullyRecovered: Boolean = false

  def postSuccessfulActorRecovery(): Unit = {}

  def receiveActorInitBaseCmd: Receive = LoggingReceive.withLabel("receiveActorInitBaseCmd") {
    case ActorInitializedPostRecovery =>
      context.become(receiveCommand)
      logger.debug("actor initialized successfully, if there are any stashed commands they will be executed",
        (LOG_KEY_PERSISTENCE_ID, persistenceId))

      val curTime = LocalDateTime.now
      val millis = ChronoUnit.MILLIS.between(preStartTime, curTime)
      logger.debug(s"[$actorId] start-time-in-millis: $millis")
      isSuccessfullyRecovered = true
      postSuccessfulActorRecovery()
      unstashAll()

    case aif: ActorInitPostRecoveryFailed =>
      context.become(receiveWhenActorInitFailedBaseCmd)
      logger.error("actor initialization failed",
        (LOG_KEY_PERSISTENCE_ID, persistenceId), (LOG_KEY_ERR_MSG, aif.error.getMessage))
      unstashAll()
      self ! Kill

    case x =>
      logger.debug(s"actor is not yet initialized, stashing command $x for now and it will be executed as soon as actor is ready",
        (LOG_KEY_PERSISTENCE_ID, persistenceId))
      stash()
  }

  def receiveWhenActorInitFailedBaseCmd: Receive = LoggingReceive.withLabel("receiveWhenActorInitFailedBaseCmd") {
    case _ => sender ! ActorInitPostRecoveryFailed
  }

  def applyReceivedEvent(evt: Any): Unit = {
    try {
      receiveEvent(evt)
      logger.trace("event recovered", (LOG_KEY_PERSISTENCE_ID, persistenceId), ("event", evt.getClass.getSimpleName))
    } catch {
      case e: Exception =>
        logger.error(s"event not handled by event receiver: ${e.getMessage}", (LOG_KEY_PERSISTENCE_ID, persistenceId), ("event", evt.getClass.getSimpleName))
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        throw e
    }
  }

  def getDeserializedEvent(te: TransformedEvent): Any = {
    try {
      val dse = eventSerializer.getDeSerializedEvent(te, persistenceEncryptionKey)
      AppStateManager.recoverIfNeeded(CONTEXT_EVENT_DECRYPTION)
      dse
    } catch {
      case e: Exception =>
        val errorMsg = s"error while decrypting persisted event (persistence-id: $persistenceId)"
        handleRecoveryFailure(e, errorMsg)
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        throw e
    }
  }

  /*
  WARNING: This exists for already existing events in our databases
  Only old events should pass through here when they are deserialized.
  No new Events should use this. When we migrate all old events, this
  should be removed.
   */
  def handledTransformedMultiEvents(tme: TransformedMultiEvents): Unit = {
    val events = tme.events.map(getDeserializedEvent)
    events.foreach(applyReceivedEvent)
    incrementTotalRecoveredEvent()
  }


  def handledTransformedMultiEvent(tme: TransformedMultiEvent): Unit = {
    val events = tme.events.map(getDeserializedEvent)
    events.foreach(applyReceivedEvent)
    incrementTotalRecoveredEvent()
  }

  def handledTransformedEvent(te: TransformedEvent): Unit = {
    val evt: Any = getDeserializedEvent(te)
    applyReceivedEvent(evt)
    incrementTotalRecoveredEvent()
  }

  def handleRecoveryCompleted(): Unit = {
    runWithInternalSpan("handleRecoveryCompleted", "BasePersistentActor") {
      val curTime = LocalDateTime.now
      val millis = ChronoUnit.MILLIS.between(preStartTime, curTime)
      if (millis > warnRecoveryTime)
        logger.warn(s"[$actorId] recovery completed (total events: $lastSequenceNr, time taken (in millis):  $millis",
          (LOG_KEY_PERSISTENCE_ID, persistenceId))
      else
        logger.debug(s"[$actorId] recovery completed (total events: $lastSequenceNr, time taken (in millis):  $millis",
          (LOG_KEY_PERSISTENCE_ID, persistenceId))
      AppStateManager.recoverIfNeeded(CONTEXT_EVENT_RECOVERY)
      postRecoveryCompleted()
    }
  }

  def handleEvent: Receive = {

    case RecoveryCompleted => handleRecoveryCompleted()

    /*
    WARNING: This exists for already existing events in our databases
    Only old events should pass through here when they are deserialized.
    No new Events should use this. When we migrate all old events, this
    should be removed.
     */
    case tme: TransformedMultiEvent => handledTransformedMultiEvent(tme)

    case tme: TransformedMultiEvents => handledTransformedMultiEvents(tme)

    case te: TransformedEvent => handledTransformedEvent(te)

    case evt: Any => applyReceivedEvent(evt)
  }

  def handleErrorEventParam(errorEventParam: ErrorEventParam): Unit = {
    AppStateManager << errorEventParam
    throw errorEventParam.cause
  }

  def handlePersistenceFailure(cause: Throwable, errorMsg: String): Unit = {
    handleErrorEventParam(ErrorEventParam(SeriousSystemError, CONTEXT_EVENT_PERSIST, cause, Option(errorMsg)))
  }

  def handleRecoveryFailure(cause: Throwable, errorMsg: String): Unit = {
    handleErrorEventParam(ErrorEventParam(SeriousSystemError, CONTEXT_EVENT_RECOVERY, cause, Option(errorMsg)))
  }

  override def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
    val errorMsg =
      s"actor persist event (${event.getClass}) failed (" +
        "possible-causes: database not reachable/up/responding, required tables are not created etc, " +
        s"persistence-id: $persistenceId, " +
        s"error-msg: ${cause.getMessage})"
    trackPersistenceFailure()
    handlePersistenceFailure(cause, errorMsg)
  }

  override def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
    val errorMsg =
      s"actor persist event (${event.getClass}) rejected (" +
        "possible-causes: database not reachable/up/responding, required tables are not created etc, " +
        s"persistence-id: $persistenceId, " +
        s"error-msg: ${cause.getMessage})"
    trackPersistenceFailure()
    handlePersistenceFailure(cause, errorMsg)
  }

  final override def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
    event match {
      case Some(_) =>
        val errorMsg = "actor not able to start (" +
          "possible-causes: unknown, "+
          s"persistence-id: $persistenceId, " +
          s"error-msg: ${cause.getMessage})"
        handleRecoveryFailure(cause, errorMsg)
      case None =>
        val errorMsg = "actor not able to start (" +
          "possible-causes: database not reachable/up/responding, required tables are not created etc, " +
          s"persistence-id: $persistenceId, " +
          s"error-msg: ${cause.getMessage})"
        handleRecoveryFailure(cause, errorMsg)
    }
  }

  def handleDeleteMsgSuccess(dms: DeleteMessagesSuccess): Unit = {
    logger.debug("old messages deleted successfully", (LOG_KEY_PERSISTENCE_ID, persistenceId),
      ("seq_num", dms.toSequenceNr))
  }

  def handleDeleteMsgFailure(dmf: DeleteMessagesFailure): Unit = {
    logger.error("could not delete old messages", (LOG_KEY_PERSISTENCE_ID, persistenceId),
      ("seq_num", dmf.toSequenceNr), (LOG_KEY_ERR_MSG, dmf.cause))
  }

  def receiveActorInitSpecificCmd: Receive = {
    case "actor initialization cmd" =>
  }

  def receiveActorInitHandler: Receive = receiveActorInitSpecificCmd orElse receiveActorInitBaseCmd

  def receiveCmd: Receive
  final override def cmdHandler: Receive = receiveCmd

  /**
   * any unhandled messages from implementing actor will be handled by this receiver
   * @return
   */
  def receiveCmdBase: Receive = deleteMsgCallbackCommandHandler orElse {
    case m => handleException(new BadRequestErrorException(
        UNSUPPORTED_MSG_TYPE.statusCode, Option(s"[$persistenceId] unsupported incoming message: $m")), sender())
  }

  override def receiveCommand: Receive = handleCommand(cmdHandler) orElse receiveCmdBase

  def receiveEvent: Receive
  override def receiveRecover: Receive = handleEvent

  final override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    logCrashReason(reason, message)
    super.preRestart(reason, message)
  }
}

trait HasActorResponseTimeout {
  def appConfig: AppConfig

  implicit lazy val duration: FiniteDuration =
    buildDuration(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ASK_TIMEOUT_IN_SECONDS)
  implicit lazy val akkActorResponseTimeout: Timeout = Timeout(duration)
}


trait EventPersistenceEncryption {
  def persistenceEncryptionKey: String
}
