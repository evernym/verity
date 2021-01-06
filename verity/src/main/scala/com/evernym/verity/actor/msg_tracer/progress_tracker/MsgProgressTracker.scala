package com.evernym.verity.actor.msg_tracer.progress_tracker

import akka.pattern.ask
import java.time.Instant

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.constants.ActorNameConstants.{MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME, SINGLETON_PARENT_PROXY}
import com.evernym.verity.actor.node_singleton.MsgProgressTrackerCache
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, SendCmdToAllNodes, StartProgressTracking, StopProgressTracking}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.Util.getActorRefFromSelection
import com.evernym.verity.ReqId
import org.apache.http.conn.util.InetAddressUtils
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.base.{CoreActorExtended, Done}
import com.evernym.verity.constants.Constants


//NOTE: This is NOT a feature code, its a utility code to see/troubleshoot msg progress in a system

object MsgProgressTracker {
  def props(appConfig: AppConfig): Props = Props(new MsgProgressTracker(appConfig))
}

/**
 * msg progress tracker sharded actor
 * @param appConfig app config
 */
class MsgProgressTracker(val appConfig: AppConfig) extends CoreActorExtended with HasActorResponseTimeout {

  implicit val isGlobalOrIpAddress: Boolean =
    InetAddressUtils.isIPv4Address(entityId) ||
      entityId == "global"

  lazy val maxRequestToRecord: Option[Int] = if (isGlobalOrIpAddress) Some(2000) else Some(5000)

  val defaultTrackingExpiryTimeInMinutes: Int = if (isGlobalOrIpAddress) 10 else 30

  var trackingStarted: Instant  = Instant.now()
  var finishTrackingAt: Instant = Instant.now().plusMillis(300)
  var requests = List.empty[State]
  var otherStartedTrackingIds = List.empty[String]

  override def receiveCmd: Receive = {
    case ct: ConfigureTracking    => handleConfigureTracking(ct)
    case te: RecordEvent          => handleRecordEvent(te)
    case ge: GetRecordedRequests  => handleGetRequests(ge)
    case CheckForPeriodicTask     => finishTrackingIfExceededTime()
  }

  def handleGetRequests(ge: GetRecordedRequests): Unit = {

    val filteredRequests: List[State] = {
      val reqIdFiltered = ge.reqId match {
        case Some(rId)  => requests.filter(_.reqId==rId)
        case None       => requests
      }
      val domainFiltered = ge.domainTrackingId match {
        case Some(tId)  => reqIdFiltered.filter(_.summary.trackingParam.domainTrackingId.contains(tId))
        case None       => reqIdFiltered
      }
      val relTrackingFiltered = ge.relTrackingId match {
        case Some(cId)  => domainFiltered.filter(_.summary.trackingParam.relTrackingId.contains(cId))
        case None       => domainFiltered
      }
      ge.withEvents match {
        case Some(ans) if ans == Constants.YES => relTrackingFiltered
        case _                                 => relTrackingFiltered.map(_.copy(events = None))
      }
    }
    sender ! RecordedRequests(filteredRequests, finishTrackingAt)
  }

  def handleRecordEvent(re: RecordEvent): Unit = {
    processEvent(re.event)
    val prevReqDetail = requests.find(_.reqId==re.reqId).getOrElse(State(re.reqId, clientIpAddress = re.clientIpAddress))
    val updatedReqDetail = prevReqDetail.updateRequestDetail(re.event)
    requests = updatedReqDetail +: requests.filterNot(_.reqId == re.reqId)
    removeOldRequestsIfMaxSizeExceeded()
    sender ! Done
  }

  def processEvent(eventParam: EventParam): Unit = eventParam.event match {
    case ips: EventInMsgProcessingStarted =>
      ips.protoParam.pinstId.foreach { pid =>
        if (! MsgProgressTrackerCache.isTracked(pid)) {
          otherStartedTrackingIds = otherStartedTrackingIds :+ pid
          singletonParentProxyActor ? SendCmdToAllNodes(StartProgressTracking(pid))
        }
      }
    case _  => //nothing to do
  }

  def removeOldRequestsIfMaxSizeExceeded(): Unit = {
    maxRequestToRecord.foreach { mrs =>
      requests = requests.take(mrs)
    }
  }

  def handleConfigureTracking(ct: ConfigureTracking): Unit = {
    if (ct.stopNow) {
      MsgProgressTrackerCache.stopProgressTracking(entityId)
      sender ! TrackingConfigured("tracking stopped")
      finishTracking()
    } else {
      ct.trackForMinutes.foreach(setTrackingExpiryTime)
      sender ! TrackingConfigured(s"tracking will be finished at: $finishTrackingAt")
      finishTrackingIfExceededTime()
    }
  }

  def setTrackingExpiryTime(addMinutes: Int): Unit = {
    finishTrackingAt = Instant.now().plusMillis(addMinutes*60*1000)
  }

  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)

  def isTrackingExpired: Boolean =
    Instant.now().toEpochMilli > finishTrackingAt.toEpochMilli

  def finishTrackingIfExceededTime(): Unit = {
    if (isTrackingExpired) {
      val result = singletonParentProxyActor ? SendCmdToAllNodes(StopProgressTracking(entityId))
      result.map { _ =>
        finishTracking()
      }
    }
  }

  def finishTracking(): Unit = {
    otherStartedTrackingIds.foreach { tid =>
      region ! ForIdentifier(tid, ConfigureTracking(stopNow = true))
    }
    stopActor()
  }

  setTrackingExpiryTime(defaultTrackingExpiryTimeInMinutes)

  scheduleJob(
    "CheckForPeriodicTask",
    300,
    CheckForPeriodicTask
  )

  lazy val region: ActorRef = ClusterSharding(context.system).shardRegion(MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME)
}

trait ProgressTrackerMsg extends ActorMessage


//commands

case class RecordEvent(reqId: ReqId, event: EventParam, clientIpAddress:Option[String]=None) extends ProgressTrackerMsg

case class GetRecordedRequests(reqId: Option[ReqId]=None,
                               domainTrackingId: Option[String]=None,
                               relTrackingId: Option[String]=None,
                               withEvents: Option[String]=None) extends ProgressTrackerMsg

case class RecordedRequests(requests: List[State], expiryTime: Instant) extends ProgressTrackerMsg

case class ConfigureTracking(trackForMinutes: Option[Int] = None,
                             stopNow: Boolean=false) extends ProgressTrackerMsg

case class TrackingConfigured(message: String) extends ProgressTrackerMsg

case object CheckForPeriodicTask extends ProgressTrackerMsg

case class EventParam(event: Any, atEpochMillis: Long = Instant.now().toEpochMilli)