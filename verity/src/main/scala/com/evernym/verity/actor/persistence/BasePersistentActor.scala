package com.evernym.verity.actor.persistence

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import akka.actor.{Kill, Stash}
import akka.event.LoggingReceive
import akka.persistence._
import akka.util.Timeout
import com.evernym.agency.common.actor.{TransformedEvent, TransformedMultiEvents}
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.util2.Exceptions._
import com.evernym.verity.util2.Status.UNSUPPORTED_MSG_TYPE
import com.evernym.verity.actor._
import com.evernym.verity.actor.appStateManager.{ErrorEvent, RecoverIfNeeded, SeriousSystemError}
import com.evernym.verity.actor.base.CoreActorExtended
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.config.{AppConfig, ConfigUtil}
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.observability.metrics.CustomMetrics._
import com.evernym.verity.protocol.engine.MultiEvent
import com.evernym.verity.util.Util._
import com.evernym.verity.actor.persistence.transformer_registry.HasTransformationRegistry
import com.evernym.verity.observability.logs.LoggingUtil
import com.evernym.verity.observability.metrics.InternalSpan
import com.evernym.verity.transformations.transformers.<=>
import com.evernym.verity.util2.Exceptions
import com.typesafe.scalalogging.Logger
import scalapb.GeneratedMessage

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * base class for almost all persistent actors used in this codebase
 */

trait BasePersistentActor
  extends PersistentActor
    with EventPersistenceEncryption
    with CoreActorExtended
    with HasActorResponseTimeout
    with DeleteMsgHandler
    with HasTransformationRegistry
    with PersistentEntityIdentifier
    with Stash
    with HasExecutionContextProvider {

  private implicit def executionContext: ExecutionContext = futureExecutionContext

  var totalPersistedEvents: Int = 0
  var totalRecoveredEvents: Int = 0
  var isAnySnapshotApplied: Boolean = false

  def incrementTotalPersistedEvents(by: Int = 1): Unit = {
    totalPersistedEvents = totalPersistedEvents + by
  }

  def incrementTotalRecoveredEvents(by: Int = 1): Unit = {
    totalRecoveredEvents = totalRecoveredEvents + by
  }

  val defaultReceiveTimeoutInSeconds = 600
  val entityCategory: String = PERSISTENT_ACTOR_BASE

  def emptyEventHandler(event: Any): Unit = {}

  def applyEvent(evt: Any): Unit = receiveRecover(evt)

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

  /**
   * transformer used for event persistence
   */
  lazy val eventTransformer: Any <=> PersistentMsg = persistenceTransformerV1

  /**
   * transforms given event by using the "eventTransformer" to a
   * generic proto buf wrapper message for which a serialization binding is
   * configured to use "ProtoBufSerializer"
   *
   * @param evt event
   * @return
   */
  def transformedEvent(evt: Any): GeneratedMessage = {
    try {
      evt match {
        case me: MultiEvent   => PersistentMultiEventMsg(me.evts.map(eventTransformer.execute))
        case e                => eventTransformer.execute(e)
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
    metricsWriter.gaugeIncrement(AS_SERVICE_DYNAMODB_PERSIST_FAILED_COUNT)
    //TODO: is below metrics needs to be captured in case of failure too?
    metricsWriter.gaugeIncrement(AS_SERVICE_DYNAMODB_PERSIST_DURATION, duration)
  }

  private def trackPersistenceSuccess(): Unit = {
    val duration = System.currentTimeMillis() - persistStart
    metricsWriter.gaugeIncrement(AS_SERVICE_DYNAMODB_PERSIST_SUCCEED_COUNT)
    metricsWriter.gaugeIncrement(AS_SERVICE_DYNAMODB_PERSIST_DURATION, duration)
  }

  private final def persistEvent(events: List[Any], sync: Boolean)(handler: Any => Unit): Unit = {
    persistStart = System.currentTimeMillis()
    val successHandler = handler andThen { _ =>
      trackPersistenceSuccess()
      publishAppStateEvent(RecoverIfNeeded(CONTEXT_EVENT_PERSIST))
    }

    try {
      val eventBatch = events.map(transformedEvent)
      eventBatch.foreach(te => PersistenceSerializerValidator.validate(te, appConfig))
      //NOTE: in below if/else block, we are using `persist` and `persistAsync`
      // DON'T use `persistAll` or `persistAllAsync` as it has a bug (see details in VE-2396)
      // which causes event overwrite and off course that will mostly
      // break the functionality provided by the actor.
      if (sync) {
        eventBatch.foreach(super.persist(_)(successHandler))
      } else {
        eventBatch.foreach(super.persistAsync(_)(successHandler))
      }
      incrementTotalPersistedEvents(eventBatch.size)
    } catch {
      case e: Exception =>
        val allEventNames = events.map(_.getClass.getSimpleName).mkString(", ")
        val errorMsg = s"[$persistenceId] error during persisting actor event $allEventNames: ${Exceptions.getErrorMsg(e)}"
        trackPersistenceFailure()
        handlePersistenceFailure(e, errorMsg)
    }
  }

  protected final def persistExt(event: Any)(handler: Any => Unit): Unit = {
    persistEvent(List(event), sync = true)(handler)
  }

  private final def persistExtAll(events: List[Any])(handler: Any => Unit): Unit = {
    persistEvent(events, sync = true)(handler)
  }

  private final def persistAsyncExt(event: Any)(handler: Any => Unit): Unit = {
    persistEvent(List(event), sync = false)(handler)
  }

  private final def persistAsyncAllExt(events: List[Any])(handler: Any => Unit): Unit = {
    persistEvent(events, sync = false)(handler)
  }

  def writeWithoutApply(event: Any): Unit = persistExt(event)(emptyEventHandler)

  def writeAllWithoutApply(events: List[Any]): Unit = persistExtAll(events)(emptyEventHandler)

  def asyncWriteWithoutApply(event: Any): Unit = persistAsyncExt(event)(emptyEventHandler)

  def asyncWriteWithoutApplyAll(events: List[Any]): Unit = persistAsyncAllExt(events)(emptyEventHandler)

  def writeAndApply(evt: Any): Unit = persistExt(evt)(receiveRecover)

  def writeAndApplyAll(events: List[Any]): Unit = {
    metricsWriter.runWithSpan("writeAndApplyAll", "BasePersistentActor", InternalSpan) {
      persistExtAll(events)(receiveRecover)
    }
  }

  def asyncWriteAndApply(evt: Any): Unit= {
    metricsWriter.runWithSpan("asyncWriteAndApply", "BasePersistentActor", InternalSpan) {
      asyncWriteWithoutApply(evt)
      applyEvent(evt)
    }
  }

  def asyncWriteAndApplyAll(events: List[Any]): Unit= {
    metricsWriter.runWithSpan("asyncWriteAndApplyAll", "BasePersistentActor", InternalSpan) {
      asyncWriteWithoutApplyAll(events)
      events.map(applyEvent)
    }
  }

  /**
   * writes/persists events and then applies the event (state change)
   * and then that event is sent back to the command/message sender
   * @param evt event
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

  private val defaultWarnRecoveryTimeInMilliSeconds: Int = 1000

  private lazy val warnRecoveryTime: Int = appConfig.getIntOption(PERSISTENT_PROTOCOL_WARN_RECOVERY_TIME_MILLISECONDS)
    .getOrElse(defaultWarnRecoveryTimeInMilliSeconds)

  override def beforeStart(): Unit = {
    logger.debug("in pre-start", (LOG_KEY_PERSISTENCE_ID, persistenceId))
    context.become(receiveActorInitHandler)
  }

  protected def normalizedEntityCategoryName: String = {
    entityCategory.replace("$", "")
  }

  protected def normalizedEntityType: String = {
    if (entityType == "/") getClass.getSimpleName.replace("$", "")
    else entityType.replace("$", "")
  }

  protected def normalizedEntityId: String = entityId.replace("$", "")

  protected def entityReceiveTimeout: Duration = ConfigUtil.getReceiveTimeout(
    appConfig, defaultReceiveTimeoutInSeconds,
    normalizedEntityCategoryName, normalizedEntityType, normalizedEntityId)

  /**
   * configuration to decide if this persistent actor should use snapshot during recovery
   * @return
   */
  protected def recoverFromSnapshot: Boolean = PersistentActorConfigUtil.getRecoverFromSnapshot(
    appConfig, defaultValue = true,
    normalizedEntityCategoryName, normalizedEntityType, normalizedEntityId)

  /**
   * use 'recoverFromSnapshot' configuration to decide if snapshot will be used during recovery or not
   * @return
   */
  override def recovery: Recovery = {
    if (recoverFromSnapshot) Recovery()
    else Recovery(fromSnapshot = SnapshotSelectionCriteria.None)
  }

  def receiveActorInitBaseCmd: Receive = LoggingReceive.withLabel("receiveActorInitBaseCmd") {
    case PostRecoveryActorInitSucceeded =>
      isSuccessfullyRecovered = true
      context.become(receiveCommand)

      val curTime = LocalDateTime.now
      val millis = ChronoUnit.MILLIS.between(postActorRecoveryStarted, curTime)
      logger.debug(s"post actor recovery finished, time taken (in millis): $millis", (LOG_KEY_PERSISTENCE_ID, persistenceId))
      logger.debug("actor initialized successfully, if there are any stashed commands they will be executed",
        (LOG_KEY_PERSISTENCE_ID, persistenceId))

      postSuccessfulActorRecovery()
      executeOnPostActorRecovery()
      unstashAll()

    case prf: PostRecoveryActorInitFailed =>
      context.become(receiveWhenActorInitFailedBaseCmd)
      logger.error("actor initialization failed",
        (LOG_KEY_PERSISTENCE_ID, persistenceId), (LOG_KEY_ERR_MSG, prf.error.getMessage))
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

  def handleEvent: Receive = {
    case RecoveryCompleted              => handleRecoveryCompleted()

    case tmes: TransformedMultiEvents   => undoTransformAndApplyEvents(tmes.events)     //legacy/deprecated multi event (java serialized)
    case dmem: DeprecatedMultiEventMsg  => undoTransformAndApplyEvents(dmem.events)     //legacy/deprecated multi event (proto buf serialized)
    case dem: DeprecatedEventMsg        => undoTransformAndApplyEvents(Seq(dem))        //legacy/deprecated event (proto buf serialized)

    case pmem: PersistentMultiEventMsg  => undoTransformAndApplyEvents(pmem.events)     //new persistent multi event msg
    case pm: PersistentMsg              => undoTransformAndApplyEvents(Seq(pm))         //new persistent msg

    case evt: Any                       => applyReceivedEvent(evt)
  }

  def handleRecoveryCompleted(): Unit = {
    metricsWriter.runWithSpan("handleRecoveryCompleted", "BasePersistentActor", InternalSpan) {
      val curTime = LocalDateTime.now
      val millis = ChronoUnit.MILLIS.between(preStartTime, curTime)
      val actorRecoveryMsg = s"[$actorId] actor recovery completed (" +
        s"lastSequenceNr: $lastSequenceNr, " +
        s"isAnySnapshotApplied: $isAnySnapshotApplied, " +
        s"totalRecoveredEvents: $totalRecoveredEvents, " +
        s"timeTakenInMillis: $millis)"
      if (millis > warnRecoveryTime) logger.warn(actorRecoveryMsg, (LOG_KEY_PERSISTENCE_ID, persistenceId))
      else logger.info(actorRecoveryMsg, (LOG_KEY_PERSISTENCE_ID, persistenceId))

      publishAppStateEvent(RecoverIfNeeded(CONTEXT_EVENT_RECOVERY))
      postRecoveryCompleted()
    }
  }

  /**
   * called after being reconstituted from event-sourced material.
   */
  var postActorRecoveryStarted = LocalDateTime.now
  def postRecoveryCompleted(): Unit = {
    postActorRecoveryStarted = LocalDateTime.now
    metricsWriter.runWithSpan("postRecoveryCompleted", "BasePersistentActor", InternalSpan) {
      context.setReceiveTimeout(entityReceiveTimeout)
      logger.debug("post actor recovery started", (LOG_KEY_PERSISTENCE_ID, persistenceId))
      basePostActorRecoveryCompleted()
    }
  }

  def postActorRecoveryCompleted(): Future[Any] = Future.successful("Done")

  /**
   * to be overridden by implementing class to run any logic post actor recovery
   * but before actor starts processing any incoming message
   * @return
   */
  def basePostActorRecoveryCompleted(): Unit = {
    postActorRecoveryCompleted().map { _ =>
      self ! PostRecoveryActorInitSucceeded
    }.recover {
      case e: RuntimeException =>
        logger.error("error while actor recovery: " + e.getMessage, (LOG_KEY_PERSISTENCE_ID, persistenceId))
        self ! ActorInitPostRecoveryFailed
    }
  }

  def undoTransformAndApplyEvents(transformedEvents: Seq[Any]): Unit = {
    val events = transformedEvents.map(untransformedEvent)
    events.foreach(applyReceivedEvent)
    incrementTotalRecoveredEvents()
  }

  /**
   * transforms given generic proto buf wrapper message (TransformedEvent, DeprecatedEventMsg, PersistentMsg)
   * by using a 'transformer' to a plain (decrypted and deserialized) event object
   * which actor can apply to it's state
   *
   * instead of hardcoding transformer for this 'undo' operation, we lookup appropriate transformer based on
   * 'transformationId' available in serialized event to make sure it is backward compatible.
   *
   * @param persistedEvent persistent event (legacy or new)
   * @return
   */
  private def untransformedEvent(persistedEvent: Any): Any = {
    try {
      val event = persistedEvent match {
        case te: TransformedEvent     =>    //deprecated java serialized event
          val pem = DeprecatedEventMsg(te.transformationId, te.eventCode, te.data)
          lookupTransformer(pem.transformationId, Option(LEGACY_PERSISTENT_OBJECT_TYPE_EVENT)).undo(pem)
        case dem: DeprecatedEventMsg  =>    //deprecated proto buf serialized event
          lookupTransformer(dem.transformationId, Option(LEGACY_PERSISTENT_OBJECT_TYPE_EVENT)).undo(dem)
        case pm: PersistentMsg        =>
          lookupTransformer(pm.transformationId).undo(pm)
      }
      publishAppStateEvent(RecoverIfNeeded(CONTEXT_EVENT_TRANSFORMATION_UNDO))
      event
    } catch {
      case e: Exception =>
        val errorMsg = s"[$persistenceId] error while undoing persisted event transformation"
        handleUndoTransformFailure(e, errorMsg)
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        throw e
    }
  }

  def applyReceivedEvent(evt: Any): Unit = {
    try {
      receiveEvent(evt)
      postEventHandlerApplied()
      logger.trace(s"[$persistenceId] event recovered", ("event", evt.getClass.getSimpleName))
    } catch {
      case e: Exception =>
        logger.error(s"[$persistenceId] event not handled by event receiver: ${e.getMessage}", ("event", evt.getClass.getSimpleName))
        logger.error(Exceptions.getStackTraceAsSingleLineString(e))
        throw e
    }
  }

  def postEventHandlerApplied(): Unit = {
    if (recoveryFinished) {  //we don't want to save snapshot while actor is in process of recovery
      executeOnStateChangePostRecovery()
    }
  }

  /**
   * determines if actor successfully recovered and executed any
   * 'postActorRecoveryCompleted' futures also got successfully executed
   */
  var isSuccessfullyRecovered: Boolean = false
  def postSuccessfulActorRecovery(): Unit = {}
  def executeOnPostActorRecovery(): Unit = {}
  def executeOnStateChangePostRecovery(): Unit = {}

  def handleErrorEventParam(errorEventParam: ErrorEvent): Unit = {
    publishAppStateEvent(errorEventParam)
    log.error(errorEventParam.cause.getMessage)
  }

  def handlePersistenceFailure(cause: Throwable, errorMsg: String): Unit = {
    handleErrorEventParam(ErrorEvent(SeriousSystemError, CONTEXT_EVENT_PERSIST, cause, Option(errorMsg)))
  }

  def handleUndoTransformFailure(cause: Throwable, errorMsg: String): Unit = {
    handleErrorEventParam(ErrorEvent(SeriousSystemError, CONTEXT_EVENT_TRANSFORMATION_UNDO, cause, Option(errorMsg)))
  }

  override def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit = {
    val errorMsg =
      s"[$persistenceId] actor persist event (${event.getClass.getSimpleName}) failed (" +
        s"error-msg: ${cause.getMessage}, " +
        s"possible-causes: $JOURNAL_ERROR_POSSIBLE_CAUSE)"

    trackPersistenceFailure()
    handlePersistenceFailure(cause, errorMsg)
  }

  override def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
    val errorMsg =
      s"[$persistenceId] " +
        s"actor persist event (${event.getClass.getSimpleName}) rejected (" +
        s"error-msg: ${cause.getMessage}, " +
        s"possible-causes: $JOURNAL_ERROR_POSSIBLE_CAUSE)"

    trackPersistenceFailure()
    handlePersistenceFailure(cause, errorMsg)
  }

  override def onRecoveryFailure(cause: Throwable, event: Option[Any]): Unit = {
    event match {
      case Some(event) =>
        logger.error(s"[$persistenceId] error while applying event ${event.getClass.getSimpleName}: ${Exceptions.getStackTraceAsSingleLineString(cause)}")
      case None =>
        logger.error(s"[$persistenceId] error while actor recovery, " +
          s"possible-causes: $JOURNAL_ERROR_POSSIBLE_CAUSE " +
          s"(error message: ${Exceptions.getStackTraceAsSingleLineString(cause)})"
        )
    }
  }

  private val JOURNAL_ERROR_POSSIBLE_CAUSE =
    "database not reachable/responding, required tables are not created, permission issues etc"

  def receiveActorInitSpecificCmd: Receive = PartialFunction.empty

  /**
   * default receiver when this persistent actor starts
   * @return
   */
  def receiveActorInitHandler: Receive = receiveActorInitSpecificCmd orElse receiveActorInitBaseCmd


  /**
   * any unhandled messages from implementing actor will be handled by this receiver
   * @return
   */
  def receiveUnhandled: Receive = {
    case m =>
      handleException(new BadRequestErrorException(
        UNSUPPORTED_MSG_TYPE.statusCode, Option(s"[$persistenceId] unsupported incoming message: $m")), sender())
  }

  private def handleBasePersistenceCmd: Receive = {
    case GetPersistentActorDetail     =>
      sender ! PersistentActorDetail(actorDetail, persistenceId, totalPersistedEvents, totalRecoveredEvents)
  }

  def basePersistentCmdHandler(actualReceiver: Receive): Receive =
    handleBasePersistenceCmd orElse
      msgDeleteCallbackHandler orElse
      extendedCoreCommandHandler(actualReceiver)

  override def receiveCommand: Receive =
    basePersistentCmdHandler(cmdHandler) orElse
      receiveUnhandled

  override def setNewReceiveBehaviour(receiver: Receive, discardOld: Boolean = true): Unit = {
    context.become(basePersistentCmdHandler(receiver), discardOld)
  }

  override def receiveRecover: Receive = handleEvent

  def receiveCmd: Receive
  def receiveEvent: Receive

  protected lazy val logger: Logger = LoggingUtil.getLoggerByClass(this.getClass)
}

trait HasActorResponseTimeout {
  def appConfig: AppConfig

  protected implicit lazy val duration: FiniteDuration =
    buildDuration(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)
  implicit lazy val responseTimeout: Timeout = Timeout(duration)
}


trait EventPersistenceEncryption {
  def persistenceEncryptionKey: String
}
