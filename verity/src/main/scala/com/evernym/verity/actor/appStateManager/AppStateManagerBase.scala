package com.evernym.verity.actor.appStateManager

import java.time.ZonedDateTime
import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.MemberStatus.{Down, Removed}
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.util2.Exceptions.{HandledErrorException, TransitionHandlerNotProvidedException}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.actor.appStateManager.state._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.{APP_STATE_MANAGER_STATE_DRAINING_DELAY_BEFORE_LEAVING_CLUSTER_IN_SECONDS, APP_STATE_MANAGER_STATE_DRAINING_DELAY_BETWEEN_STATUS_CHECKS_IN_SECONDS, APP_STATE_MANAGER_STATE_DRAINING_MAX_STATUS_CHECK_COUNT, APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_COUNT, APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_DURATION}
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.AppVersion
import com.evernym.verity.observability.logs.LoggingUtil
import com.evernym.verity.util2.{ExceptionConverter, Exceptions}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}


trait AppStateManagerBase extends HasExecutionContextProvider { this: Actor =>

  private implicit val ex: ExecutionContext = futureExecutionContext

  val appConfig: AppConfig
  val appVersion: AppVersion
  val notifierService: SysServiceNotifier
  val shutdownService: SysShutdownProvider

  //NOTE: don't change the logger name, it is tied in logback.xml to redirect this logger's output to specific log file
  val logger: Logger = LoggingUtil.getLoggerByName("AppStateManager")

  //states
  protected var events: List[EventDetail] = List.empty
  protected var currentState: AppState = _

  //mapping of state and list of causes (errors etc) responsible for that state
  protected var causesByState: Map[String, List[CauseDetail]] = Map.empty
  //mapping of context and set of causes happened in that context
  protected var causesByContext: Map[String, Set[CauseDetail]] = Map.empty

  def getState: AppState = currentState
  def getAllEvents: AllEvents = AllEvents(events)
  def getCausesByState: Map[String, List[CauseDetail]] = causesByState
  def getCausesByContext: Map[String, Set[CauseDetail]] = causesByContext
  def getCurrentStateCauses: List[CauseDetail] = causesByState.getOrElse(currentState.toString, List.empty)

  protected def getDetailedAppState: AppStateDetailed = {
    val runningVersion = s"${appVersion.major}.${appVersion.minor}.${appVersion.patch}"
    AppStateDetailed(runningVersion, getState, events, causesByState, causesByContext)
  }

  protected def getHeartbeat: StatusDetailResp = {
    import com.evernym.verity.util2.Status.{ACCEPTING_TRAFFIC, NOT_ACCEPTING_TRAFFIC}
    val currentState: AppState = getState
    currentState match {
      case _: InitializingState
           | DrainingState
           | ShutdownWithErrors
           | ShutdownState =>
        StatusDetailResp(NOT_ACCEPTING_TRAFFIC.withMessage(currentState.toString))
      case ListeningState
           | DegradedState
           | SickState =>
        StatusDetailResp(ACCEPTING_TRAFFIC.withMessage(currentState.toString))
      case _ =>
        StatusDetailResp(
          NOT_ACCEPTING_TRAFFIC.withMessage(
            s"Unknown application state: ${currentState.toString}. Assuming NOT accepting traffic."
          )
        )
    }
  }

  private def logTransitionMsg(newState: AppState, msg: String): Unit = {
    newState match {
      case SickState | ShutdownWithErrors => logger.error(msg)
      case DegradedState => logger.warn(msg)
      case _ => logger.info(msg)
    }
  }

  private def init(): Unit = {
    causesByState = Map.empty
    causesByContext = Map.empty
    lazy val maxRetryCount = appConfig.getIntOption(APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_COUNT).getOrElse(10)
    lazy val maxRetryDuration = appConfig.getIntOption(APP_STATE_MANAGER_STATE_INITIALIZING_MAX_RETRY_DURATION).getOrElse(240)
    val initState = new InitializingState(maxRetryCount, maxRetryDuration)
    events = List(EventDetail(ZonedDateTime.now(), initState, MSG_AGENT_SERVICE_INIT_STARTED))
    currentState = initState
    logTransitionedMsg(currentState, Option(MSG_AGENT_SERVICE_INIT_STARTED))
    notifierService.setStatus(STATUS_INITIALIZING)
  }

  init()

  def performTransition(newState: AppState, param: EventParam): Unit = {
    logTransitioningMsg(currentState, newState)
    changeState(newState, param)
    addToCausesByState(param.causeDetail)
    addToCausesByContext(param.context, param.causeDetail)
    logTransitionedMsg(newState)
  }

  def startDraining(): Unit = {
    // Begin draining the node. The transition to "Draining" will schedule the node to leave the cluster (down itself)
    processSuccessEvent(SuccessEvent(DrainingStarted, CONTEXT_AGENT_SERVICE_DRAIN,
      causeDetail = CauseDetail("agent-service-draining", "agent-service-started-draining-successfully"),
      msg = Option("Informing load-balancers/clients/proxies this akka node is about to stop accepting requests.")
    ))
  }

  def reportAndStay(param: EventParam): Unit = {
    addNewEvent(currentState, param.getCauseMsg)
    logger.info(s"stayed in '${currentState.toString}'")
  }

  private def executeEvent(eventParam: EventParam): Unit = synchronized {
    if (currentState != ShutdownWithErrors && currentState != ShutdownState) {
      try {
        eventParam.event match {
          case _: ErrorEventBase => logger.error("error event occurred", (LOG_KEY_ERR_MSG, eventParam.getCauseMsg))
          case _ => logger.info(eventParam.getCauseMsg)
        }
        currentState.processEvent(eventParam)(this)
      } catch {
        case e: TransitionHandlerNotProvidedException =>
          logger.error("transition handler not provided", (LOG_KEY_ERR_MSG, e.getMessage))
      }
    }
  }

  def processErrorEvent(param: ErrorEvent): Unit = {
    val handledError = param.cause match {
      case he: HandledErrorException => he
      case e: Exception => ExceptionConverter.getHandledError(e)
    }
    val causeMsg = param.msg.getOrElse(handledError.errorDetail.map(_.toString).getOrElse(
      handledError.respMsg.getOrElse(Exceptions.getErrorMsg(handledError))))

    val paramExt = EventParam(param.event, param.context, CauseDetail(
      handledError.respCode, causeMsg))

    executeEvent(paramExt)
  }

  def processSuccessEvent(param: SuccessEvent): Unit = {
    val paramExt = EventParam(param.event, param.context, param.causeDetail)
    executeEvent(paramExt)
  }

  def recoverIfNeeded(context: String): Unit = {
    if (List(DegradedState, SickState).contains(currentState)) {
      causesByContext.getOrElse(context, Set.empty).foreach { cd =>
        val isRemoved = removeFromCausesByStateIfExists(cd)
        if (isRemoved) {
          val updatedCause = cd.copy(msg = s"recovered from cause (${cd.msg}) ${getCurrentStateCauses.size} causes remaining")
          processSuccessEvent(SuccessEvent(RecoveredFromCause, context, updatedCause))
        }
      }
      if (getCurrentStateCauses.isEmpty) {
        val cd = CauseDetail(AUTO_RECOVERED_CODE, "recovered from all causes")
        processSuccessEvent(SuccessEvent(Recovered, context, cd))
      }
    }
  }

  def changeState(newState: AppState, param: EventParam): Unit = {
    if (currentState != newState) {
      addNewEvent(newState, param.getCauseMsg)
      currentState = newState
    }
  }

  def addNewEvent(newState: AppState, cause: String): Unit = {
    //don't change the order, TE expects latest event to be at the top of the list
    events = EventDetail(ZonedDateTime.now(), newState, cause) +: events
  }

  def addToCausesByState(causeDetail: CauseDetail): Unit = {
    val currentStateCauses = getCurrentStateCauses
    if (!currentStateCauses.map(_.code).contains(causeDetail.code)) {
      //don't change the order, TE expects latest causes to be at the top of the list
      val newStateCauses = causeDetail +: currentStateCauses
      causesByState = causesByState ++ Map(currentState.toString -> newStateCauses)
    }
  }

  def addToCausesByContext(context: String, causeDetail: CauseDetail): Unit = {
    if (causeDetail.code != AUTO_RECOVERED_CODE) {
      val existingCausesByContext = causesByContext.getOrElse(context, Set.empty)
      if (!existingCausesByContext.map(_.code).contains(causeDetail.code)) {
        //don't change the order, TE expects latest causes to be at the top of the list
        val updatedCausesByContext = Set(causeDetail) ++ existingCausesByContext
        causesByContext = causesByContext + (context -> updatedCausesByContext)
      }
    }
  }

  def removeFromCausesByStateIfExists(causeDetail: CauseDetail): Boolean = {
    val currentStateCauses = getCurrentStateCauses
    val otherCauses = currentStateCauses.filterNot(_ == causeDetail)
    if (otherCauses.size != currentStateCauses.size) {
      causesByState = causesByState ++ Map(currentState.toString -> otherCauses)
      true
    } else {
      false
    }
  }

  private def logTransitioningMsg(curState: AppState, newState: AppState): Unit = {
    val msg = "transitioning from '" + curState + "' to '" + newState + "'"
    logTransitionMsg(newState, msg)
  }

  private def logTransitionedMsg(newState: AppState, causedByOpt: Option[String] = None): Unit = {
    val msg = s"transitioned to '${newState.toString}'${causedByOpt.map(cb => s" (caused by: $cb)").getOrElse("")}"
    logTransitionMsg(newState, msg)
  }

  def performServiceDrain(): Unit = {
    try {
      // TODO: Start a CoordinatedShutdown (rely on the ClusterDaemon addTask here) OR do the following?
      val cluster = Cluster(context.system)
      val f: Future[Boolean] = Future {
        val delayBeforeLeavingCluster = appConfig.getIntOption(
          APP_STATE_MANAGER_STATE_DRAINING_DELAY_BEFORE_LEAVING_CLUSTER_IN_SECONDS).getOrElse(10)
        val delayBetweenStatusChecks = appConfig.getIntOption(
          APP_STATE_MANAGER_STATE_DRAINING_DELAY_BETWEEN_STATUS_CHECKS_IN_SECONDS).getOrElse(1)
        val maxStatusCheckCount = appConfig.getIntOption(
          APP_STATE_MANAGER_STATE_DRAINING_MAX_STATUS_CHECK_COUNT).getOrElse(20)
        logger.info(
          s"""Will remain in draining state for at least $delayBeforeLeavingCluster seconds before starting the Coordinated Shutdown..."""
        )
        // Sleep a while to give the load balancers time to get a sufficient number of non-200 http response codes
        // from agency/heartbeat AFTER application state transition to 'Draining'.
        Thread.sleep(delayBeforeLeavingCluster * 1000) // seconds converted to milliseconds
        logger.info(s"Akka node ${cluster.selfAddress} is leaving the cluster...")
        // NOTE: Tasks to gracefully leave an Akka cluster include graceful shutdown of Cluster Singletons and Cluster
        //       Sharding.
        cluster.leave(cluster.selfAddress)

        def checkIfNodeHasLeftCluster(delay: Int, tries: Int): Boolean = {
          if (tries <= 0) return false
          logger.debug(s"Check if cluster is terminated or status is set to Removed or Down...")
          if (cluster.isTerminated || cluster.selfMember.status == Removed || cluster.selfMember.status == Down) {
            return true
          }
          logger.debug(s"""Cluster is NOT terminated AND status is NOT set to Removed or Down. Sleep $delay milliseconds and try again up to $tries more time(s).""")
          Thread.sleep(delay * 1000) // sleep one second and check again
          checkIfNodeHasLeftCluster(delay, tries - 1)
        }
        checkIfNodeHasLeftCluster(delayBetweenStatusChecks, maxStatusCheckCount)
      }

      f onComplete {
        case Success(true) =>
          logger.info("Akka node has left the cluster")

          logger.info(
            s"""Akka node ${cluster.selfAddress} is being marked as 'down' as well in the event of network failures while 'leaving' the cluster..."""
          )
          // NOTE: In case of network failures during this process (leaving) it might still be necessary to set the
          //       node’s status to Down in order to complete the removal.
          cluster.down(cluster.selfAddress)

          // Begin shutting down the node. The transition to "Shutdown" will stop the sysServiceNotifier and
          // perform a service shutdown (system exit).
          self ! SuccessEvent(Shutdown, CONTEXT_AGENT_SERVICE_SHUTDOWN,
            causeDetail = CauseDetail(
              "agent-service-shutdown", "agent-service-shutdown-successfully"
            ),
            msg = Option("Akka node is about to shutdown.")
          )
        case Success(false) =>
          logger.error("node timed out while attempting to 'leave' the cluster")
        case Failure(error) =>
          logger.error(
            "node encountered a failure while attempting to 'leave' the cluster.", (LOG_KEY_ERR_MSG, error)
          )
      }
    } catch {
      case e: Exception => logger.error(
        s"Failed to Drain the akka node. Reason: ${e.toString}"
      )
    }
  }
}

//api response case classes
case class EventDetailResp(date: ZonedDateTime, state: String, cause: String)

case class AppStateDetailedResp(runningVersion: String,
                                currentState: String,
                                stateEvents: List[EventDetailResp],
                                causesByState: Map[String, List[CauseDetail]],
                                causesByContext: Map[String, Set[CauseDetail]]) extends ActorMessage

case class AllEvents(events: List[EventDetail]) extends ActorMessage

case class AppStateDetailed(runningVersion: String,
                            currentState: AppState,
                            stateEvents: List[EventDetail],
                            causesByState: Map[String, List[CauseDetail]],
                            causesByContext: Map[String, Set[CauseDetail]]) extends ActorMessage {

  def toResp: AppStateDetailedResp = AppStateDetailedResp(
    runningVersion, currentState.toString, stateEvents.map(se => se.toEventDetailResp), causesByState, causesByContext
  )
}