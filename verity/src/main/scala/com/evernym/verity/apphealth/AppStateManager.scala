package com.evernym.verity.apphealth

import java.time.ZonedDateTime

import akka.actor.ActorSystem
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.Exceptions.{HandledErrorException, TransitionHandlerNotProvidedException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.apphealth.AppStateConstants._
import com.evernym.verity.apphealth.state._
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.http.common.StatusDetailResp
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.evernym.verity.util.{Util, UtilBase}
import com.evernym.verity.{AppVersion, BuildInfo}
import com.evernym.verity.{ExceptionConverter, Exceptions}
import com.typesafe.scalalogging.Logger

import scala.collection.immutable.Queue
import scala.concurrent.Future


object AppStateManager extends {
  val appVersion: AppVersion = BuildInfo.version
  val sysServiceNotifier: SysServiceNotifier = SDNotifySysServiceNotifier
  val util: UtilBase = Util
} with AppStateManagerBase


trait AppStateManagerBase {

  val appVersion: AppVersion
  val sysServiceNotifier: SysServiceNotifier
  val util: UtilBase

  //NOTE: don't change the logger name, it is tied in logback.xml to redirect this logger's output to specific log file
  val logger: Logger = getLoggerByName("AppStateManager")

  protected var eventQueue: Queue[EventParam] = Queue.empty

  //states
  protected var events: List[EventDetail] = List.empty
  protected var currentState: AppState = _

  //mapping of state and list of causes (errors etc) responsible for that state
  protected var causesByState: Map[String, List[CauseDetail]] = Map.empty

  //mapping of context and set of causes happened in that context
  protected var causesByContext: Map[String, Set[CauseDetail]] = Map.empty

  def getCurrentState: AppState = currentState

  def getEvents: List[EventDetail] = events

  def getCausesByState: Map[String, List[CauseDetail]] = causesByState

  def getCausesByContext: Map[String, Set[CauseDetail]] = causesByContext

  private def getCurrentStateCauses: List[CauseDetail] = causesByState.getOrElse(currentState.toString, List.empty)

  def getDetailedAppState: AppStateDetailedResp = {
    val stateEvents = events.map(ss => EventDetailResp(ss.date, ss.state.toString, ss.cause))
    val runningVersion = s"${appVersion.major}.${appVersion.minor}.${appVersion.patch}"
    AppStateDetailedResp(runningVersion, getCurrentState.toString, stateEvents, causesByState, causesByContext)
  }

  def getHeartbeat: StatusDetailResp = {
    import com.evernym.verity.Status.{ACCEPTING_TRAFFIC, NOT_ACCEPTING_TRAFFIC}
    val currentState: AppState = AppStateManager.getCurrentState
    currentState match {
      case InitializingState
           | DrainingState
           | ShutdownWithErrors
           | ShutdownState => StatusDetailResp(
        NOT_ACCEPTING_TRAFFIC.withMessage(currentState.toString)
      )
      case ListeningState
           | DegradedState
           | SickState => StatusDetailResp(
        ACCEPTING_TRAFFIC.withMessage(currentState.toString)
      )
      case _ => StatusDetailResp(
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
    events = List(EventDetail(ZonedDateTime.now(), InitializingState, MSG_AGENT_SERVICE_INIT_STARTED))
    currentState = InitializingState
    logTransitionedMsg(currentState, Option(MSG_AGENT_SERVICE_INIT_STARTED))
    sysServiceNotifier.setStatus(STATUS_INITIALIZING)
  }

  init()

  def performTransition(newState: AppState, param: EventParam): Unit = {
    logTransitioningMsg(currentState, newState)
    changeState(newState, param)
    addToCausesByState(param.causeDetail)
    addToCausesByContext(param.context, param.causeDetail)
    logTransitionedMsg(newState)
  }

  def drain (system: ActorSystem): Unit = {
    // Begin draining the node. The transition to "Draining" will schedule the node to leave the cluster (down itself)
    <<(SuccessEventParam(DrainingStarted, CONTEXT_AGENT_SERVICE_DRAIN,
      causeDetail = CauseDetail("agent-service-draining", "agent-service-started-draining-successfully"),
      msg = Option("Informing load-balancers/clients/proxies this akka node is about to stop accepting requests."),
      system = Option(system)
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
          case _: ErrorEvent => logger.error("error event occurred", (LOG_KEY_ERR_MSG, eventParam.getCauseMsg))
          case _ => logger.info(eventParam.getCauseMsg)
        }
        currentState.processEvent(eventParam)(this)
      } catch {
        case e: TransitionHandlerNotProvidedException =>
          logger.error("transition handler not provided", (LOG_KEY_ERR_MSG, e.getMessage))
      }
    }
  }

  private def executePendingEvents(): Unit = synchronized {
    if (eventQueue.nonEmpty) {
      val (ep, req) = eventQueue.dequeue
      eventQueue = req
      executeEvent(ep)
      executePendingEvents()
    }
  }

  private def addEventToQueue(eventParam: EventParam): Unit = synchronized {
    if (! AppConfigWrapper.isConfigLoaded) {
      logger.error(eventParam.causeDetail.toString)
    }
    eventQueue = eventQueue enqueue eventParam
    Future(executePendingEvents())
  }

  def <<(param: ErrorEventParam): Unit = {
    val handledError = param.cause match {
      case he: HandledErrorException => he
      case e: Exception => ExceptionConverter.getHandledError(e)
    }
    val causeMsg = param.msg.getOrElse(handledError.errorDetail.map(_.toString).getOrElse(
      handledError.respMsg.getOrElse(Exceptions.getErrorMsg(handledError))))

    val paramExt = EventParam(param.event, param.context, CauseDetail(
      handledError.respCode, causeMsg), param.actionHandler, param.system)

    addEventToQueue(paramExt)
  }

  def <<(param: SuccessEventParam): Unit = {
    val paramExt = EventParam(param.event, param.context, param.causeDetail, param.actionHandler, param.system)
    addEventToQueue(paramExt)
  }

  def recoverIfNeeded(context: String): Unit = {
    runWithInternalSpan("recoverIfNeeded", "AppStateManager") {
      if (List(DegradedState, SickState).contains(currentState)) {
        causesByContext.getOrElse(context, Set.empty).foreach { cd =>
          val isRemoved = removeFromCausesByStateIfExists(cd)
          if (isRemoved) {
            val updatedCause = cd.copy(msg = s"recovered from cause (${cd.msg}) ${getCurrentStateCauses.size} causes remaining")
            <<(SuccessEventParam(RecoveredFromCause, context, updatedCause))
          }
        }
        if (getCurrentStateCauses.isEmpty) {
          val cd = CauseDetail(AUTO_RECOVERED_CODE, "recovered from all causes")
          <<(SuccessEventParam(Recovered, context, cd))
        }
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

}

//api response case classes
case class EventDetailResp(date: ZonedDateTime, state: String, cause: String)
case class AppStateDetailedResp(
                                 runningVersion: String,
                                 currentState: String,
                                 stateEvents: List[EventDetailResp],
                                 causesByState: Map[String, List[CauseDetail]],
                                 causesByContext: Map[String, Set[CauseDetail]]
                               )

case class ActionHandler(actionCmdHandler: PartialFunction[Any, Any], cmd: Any)

