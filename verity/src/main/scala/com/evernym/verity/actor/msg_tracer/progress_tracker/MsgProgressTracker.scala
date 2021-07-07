package com.evernym.verity.actor.msg_tracer.progress_tracker

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime}

import akka.pattern.ask
import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.constants.ActorNameConstants.{MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME, SINGLETON_PARENT_PROXY}
import com.evernym.verity.actor.node_singleton.MsgProgressTrackerCache
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.actor.{ActorMessage, SendCmdToAllNodes, StopProgressTracking}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.Util.getActorRefFromSelection
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util2.ReqId
import com.evernym.verity.actor.base.{CoreActorExtended, DoNotRecordLifeCycleMetrics}
import com.evernym.verity.protocol.engine.MsgId
import org.apache.http.conn.util.InetAddressUtils

import scala.concurrent.Future


//NOTE: This is NOT a feature code, its a utility code to see/troubleshoot msg progress in a system

object MsgProgressTracker {
  def props(appConfig: AppConfig): Props = Props(new MsgProgressTracker(appConfig))

  def isGlobalOrIpAddress(trackingId: String): Boolean = {
    InetAddressUtils.isIPv4Address(trackingId) ||
      trackingId == MsgProgressTrackerCache.GLOBAL_TRACKING_ID
  }
}

/**
 * msg progress tracker sharded actor
 * @param appConfig app config
 */
class MsgProgressTracker(val appConfig: AppConfig)
  extends CoreActorExtended
    with DoNotRecordLifeCycleMetrics
    with HasActorResponseTimeout {

  override def receiveCmd: Receive = {
    case ct: ConfigureTracking      => handleConfigureTracking(ct)
    case rd: RecordReqData          => handleRecordData(rd)
    case rd: RecordMsgDeliveryData  => handleRecordMsgDeliveryData(rd)
    case gs: GetState               => handleGetState(gs)
    case CheckForPeriodicTask       => finishTrackingIfExceededTime()
  }

  def handleRecordMsgDeliveryData(rd: RecordMsgDeliveryData): Unit = {
    rd match {
      case romd: RecordOutMsgDeliveryEvents =>
        outMsgToReqId.get(romd.msgId).foreach { reqId =>
          reqState.get(reqId).foreach { rs =>
            romd.events.foreach { event =>
              val updatedState = rs.updateOutMsgDeliveryEvents(event.copy(msgId = Option(romd.msgId)))
              reqState = reqState + (rs.reqId -> updatedState)
            }
          }
        }
    }
  }

  def handleRecordData(rd: RecordReqData): Unit = {
    if (! reqState.contains(rd.reqId)) {
      orderedReqIds = orderedReqIds :+ rd.reqId
    }
    rd match {
        //for main events
      case rs: RecordRoutingEvent        => handleRecordRoutingEvent(rs)
      case rs: RecordInMsgEvent          => handleRecordInMsgEvent(rs)
      case rs: RecordOutMsgEvent         => handleRecordOutMsgEvent(rs)

        //for child events
      case re: RecordRoutingChildEvents  => handleRecordRoutingChildEvents(re)
      case re: RecordInMsgChildEvents    => handleRecordInMsgChildEvents(re)
      case re: RecordOutMsgChildEvents   => handleRecordOutMsgChildEvents(re)
    }
    removeOldStateItemsIfMaxSizeExceeded()
  }

  def handleRecordRoutingEvent(rs: RecordRoutingEvent): Unit = {
    val currTrackingState = reqState.getOrElse(rs.reqId, RequestState(rs.reqId))
    val updatedTrackingState = currTrackingState.updateRoutingEvents(rs.event)
    reqState = reqState + (rs.reqId -> updatedTrackingState)
  }

  def handleRecordInMsgEvent(rs: RecordInMsgEvent): Unit = {
    val currTrackingState = reqState.getOrElse(rs.reqId, RequestState(rs.reqId))
    val updatedTrackingState = currTrackingState.updateInMsgEvents(rs.event)
    reqState = reqState + (rs.reqId -> updatedTrackingState)
  }

  def handleRecordOutMsgEvent(rs: RecordOutMsgEvent): Unit = {
    val currTrackingState = reqState.getOrElse(rs.reqId, RequestState(rs.reqId))
    val updatedTrackingState = currTrackingState.updateOutMsgEvents(rs.event)
    reqState = reqState + (rs.reqId -> updatedTrackingState)
    rs.event.msgId.foreach { omi =>
      outMsgToReqId = outMsgToReqId + ( omi -> rs.reqId)
    }
  }

  def handleRecordRoutingChildEvents(re: RecordRoutingChildEvents): Unit = {
    val trackingState = reqState.getOrElse(re.reqId, RequestState(re.reqId))
    val updatedState = trackingState.updateRoutingChildEvents(re.id, re.events)
    reqState = reqState + (re.reqId -> updatedState)
  }

  def handleRecordInMsgChildEvents(re: RecordInMsgChildEvents): Unit = {
    val currState = reqState.getOrElse(re.reqId, RequestState(re.reqId))
    val updatedState = currState.updateInMsgChildEvents(re.inMsgId, re.events)
    reqState = reqState + (re.reqId -> updatedState)
  }

  def handleRecordOutMsgChildEvents(re: RecordOutMsgChildEvents): Unit = {
    val currState = reqState.getOrElse(re.reqId, RequestState(re.reqId))
    val updatedState = currState.updateOutMsgChildEvents(re.outMsgId, re.events)
    reqState = reqState + (re.reqId -> updatedState)
  }

  def handleGetState(gs: GetState): Unit = {
    val sndr = sender()
    Future {
      val candidateReqIds = {
        val reqIds = orderedReqIds.takeRight(gs.requestStateSize.getOrElse(orderedReqIds.size))
        if (gs.latestFirst) reqIds.reverse else reqIds
      }

      val orderedState = candidateReqIds.flatMap(reqId => reqState.get(reqId).map(_.prepared()))

      sndr ! RecordedStates(orderedState, finishTrackingAt)
    }
  }

  private def removeOldStateItemsIfMaxSizeExceeded(): Unit = {
    maxStateItemsToRecord.foreach { maxItemSize =>
      val reqIdsToRetain = orderedReqIds.takeRight(maxItemSize)
      val staleReqIds = orderedReqIds diff reqIdsToRetain
      orderedReqIds = reqIdsToRetain
      reqState = reqState -- staleReqIds
    }
  }

  def handleConfigureTracking(ct: ConfigureTracking): Unit = {
    if (ct.stopNow) {
      MsgProgressTrackerCache(context.system).stopProgressTracking(entityId)
      sender ! TrackingConfigured("tracking stopped")
      stopTracking()
    } else {
      if (ct.startOver) {
        cleanupState()
      }
      ct.trackForMinutes.foreach(setTrackingExpiryTime)
      sender ! TrackingConfigured(s"tracking will be finished at: $finishTrackingAt")
      finishTrackingIfExceededTime()
    }
  }

  private lazy val singletonParentProxyActor: ActorRef =
    getActorRefFromSelection(SINGLETON_PARENT_PROXY, context.system)(appConfig)

  private def isTrackingExpired: Boolean =
    Instant.now().toEpochMilli > finishTrackingAt.toEpochMilli

  private def finishTrackingIfExceededTime(): Unit = {
    if (isTrackingExpired) {
      stopTracking()
    }
  }

  private def stopTracking(): Unit = {
    val result = singletonParentProxyActor ? SendCmdToAllNodes(StopProgressTracking(entityId))
    result.map { _ =>
      stopActor()
    }
  }

  private def setTrackingExpiryTime(addMinutes: Int): Unit = {
    finishTrackingAt = Instant.now().plusMillis(addMinutes*60*1000)
  }

  implicit val isGlobalOrIpAddress: Boolean = MsgProgressTracker.isGlobalOrIpAddress(entityId)

  val defaultTrackingExpiryTimeInMinutes: Int = if (isGlobalOrIpAddress) 10 else 30
  var finishTrackingAt: Instant = Instant.now().plusSeconds(60) //this gets overridden with 'defaultTrackingExpiryTimeInMinutes'
  val maxStateItemsToRecord: Option[Int] = if (isGlobalOrIpAddress) Some(500) else Some(1000)

  var orderedReqIds = List.empty[ReqId]
  var outMsgToReqId = Map.empty[MsgId, ReqId]

  var reqState = Map.empty[ReqId, RequestState]

  type ReqId = String

  lazy val region: ActorRef = ClusterSharding(context.system).shardRegion(MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME)

  def cleanupState(): Unit = {
    orderedReqIds = List.empty
    reqState = Map.empty
    outMsgToReqId = Map.empty
  }

  setTrackingExpiryTime(defaultTrackingExpiryTimeInMinutes)

  scheduleJob(
    "CheckForPeriodicTask",
    300,
    CheckForPeriodicTask
  )
}

trait ProgressTrackerMsg extends ActorMessage


//commands

case class RequestState(reqId: String,
                        routingEvents: Option[List[RoutingEvent]] = None,
                        inMsgEvents: Option[List[MsgEvent]] = None,
                        outMsgEvents: Option[List[MsgEvent]] = None,
                        outMsgDeliveryEvents: Option[List[MsgEvent]] = None,
                        timeTakenInMillis: Option[Long] = None) {

  def routingEventsReq: List[RoutingEvent] = routingEvents.getOrElse(List.empty)
  def inMsgEventsReq: List[MsgEvent] = inMsgEvents.getOrElse(List.empty)
  def outMsgEventsReq: List[MsgEvent] = outMsgEvents.getOrElse(List.empty)
  def outMsgDeliveryEventsReq: List[MsgEvent] = outMsgDeliveryEvents.getOrElse(List.empty)

  def prepared(): RequestState = {
    val firstEventTime = routingEventsReq.headOption.map(_.recordedAt) orElse inMsgEventsReq.headOption.map(_.recordedAt)
    val lastEventTime = {
      val lastInMsgTime = inMsgEventsReq.lastOption.map { le =>
        le.childEvents.getOrElse(List.empty).lastOption.map(_.recordedAt).getOrElse(le.recordedAt)
      }
      val lastOutMsgTime = outMsgEventsReq.lastOption.map { le =>
        le.childEvents.getOrElse(List.empty).lastOption.map(_.recordedAt).getOrElse(le.recordedAt)
      }
      lastOutMsgTime orElse lastInMsgTime
    }
    val timeTaken = (firstEventTime, lastEventTime) match {
      case (Some(fre), Some(lme)) => Option(ChronoUnit.MILLIS.between(fre, lme))
      case _                      => None
    }
    copy(
      timeTakenInMillis = timeTaken
    )
  }

  def updateRoutingEvents(event: RoutingEvent): RequestState = {
    val events = routingEventsReq
    val (_, otherEvents) = events.partition(_.id == event.id)
    val updatedEvents = otherEvents :+ event
    copy(routingEvents = Option(updatedEvents))
  }

  def updateInMsgEvents(event: MsgEvent): RequestState = {
    val curEvents = inMsgEventsReq
    val updatedEvents = curEvents :+ event
    copy(inMsgEvents = Option(updatedEvents))
  }

  def updateOutMsgEvents(event: MsgEvent): RequestState = {
    val curEvents = outMsgEventsReq
    val updatedEvents = curEvents :+ event
    copy(outMsgEvents = Option(updatedEvents))
  }

  def updateOutMsgDeliveryEvents(event: MsgEvent): RequestState = {
    val curEvents = outMsgDeliveryEventsReq
    val updatedEvents = curEvents :+ event
    copy(outMsgDeliveryEvents = Option(updatedEvents))
  }

  def updateRoutingChildEvents(id: String, events: List[ChildEvent]): RequestState = {
    val currRoutingEvents = routingEvents.getOrElse(List.empty)
    val (matchedEvents, otherEvents) = currRoutingEvents.partition(_.id.contains(id))
    val routingEvent = matchedEvents.headOption.getOrElse(RoutingEvent())
    val updatedChildEvents = routingEvent.childEvents.getOrElse(List.empty) ++ events
    val updatedInEvent = routingEvent.copy(childEvents =  Option(updatedChildEvents))
    copy(routingEvents = Option(otherEvents :+ updatedInEvent))
  }

  def updateInMsgChildEvents(msgId: MsgId, events: List[ChildEvent]): RequestState = {
    val currEvents = inMsgEvents.getOrElse(List.empty)
    val (matchedEvents, otherEvents) = currEvents.partition(_.msgId.contains(msgId))
    val inEvent = matchedEvents.headOption.getOrElse(MsgEvent())
    val updatedChildEvents = inEvent.childEvents.getOrElse(List.empty) ++ events
    val updatedInEvent = inEvent.copy(childEvents =  Option(updatedChildEvents))
    copy(inMsgEvents = Option(otherEvents :+ updatedInEvent))
  }

  def updateOutMsgChildEvents(msgId: MsgId, events: List[ChildEvent]): RequestState = {
    val currEvents = outMsgEvents.getOrElse(List.empty)
    val (matchedEvents, otherEvents) = currEvents.partition(_.msgId.contains(msgId))
    val inEvent = matchedEvents.headOption.getOrElse(MsgEvent())
    val updatedChildEvents = inEvent.childEvents.getOrElse(List.empty) ++ events
    val updatedInEvent = inEvent.copy(childEvents =  Option(updatedChildEvents))
    copy(outMsgEvents = Option(otherEvents :+ updatedInEvent))
  }

  override def toString: String = {
    s"""
       |id: $reqId\n
       |routingEvents: $routingEventsReq\n
       |inMsgEvents: $inMsgEventsReq\n
       |outMsgEvents: $outMsgEventsReq\n
       |outMsgDeliveryEvents: $outMsgDeliveryEventsReq
       |""".stripMargin
  }
}

object ChildEvent {
  def apply(typ: String, detail: String): ChildEvent =
    ChildEvent(typ, Option(detail))
}
case class ChildEvent(msg: String,
                      detail: Option[String]=None,
                      recordedAt: LocalDateTime = LocalDateTime.now())

trait RecordData extends ProgressTrackerMsg

trait RecordReqData extends RecordData {
  def reqId: ReqId
}

trait RecordMsgDeliveryData extends RecordData {
  def msgId: MsgId
}

object RecordInMsgChildEvents {
  def apply(reqId: String, inMsgId: MsgId, event: ChildEvent): RecordInMsgChildEvents =
    RecordInMsgChildEvents(reqId, inMsgId, List(event))
}
case class RecordInMsgChildEvents(reqId: String, inMsgId: MsgId, events: List[ChildEvent]) extends RecordReqData

object RecordOutMsgChildEvents {
  def apply(reqId: String, outMsgId: MsgId, event: ChildEvent): RecordOutMsgChildEvents =
    RecordOutMsgChildEvents(reqId, outMsgId, List(event))
}
case class RecordOutMsgChildEvents(reqId: String, outMsgId: MsgId, events: List[ChildEvent]) extends RecordReqData

object RecordRoutingChildEvents {
  def apply(reqId: String, id: String, event: ChildEvent): RecordRoutingChildEvents =
    RecordRoutingChildEvents(reqId, id, List(event))
}
case class RecordRoutingChildEvents(reqId: String,
                                    id: String,
                                    events: List[ChildEvent]) extends RecordReqData


object MsgEvent {
  def withIdAndDetail(msgId: String, detail: String): MsgEvent =
    MsgEvent(msgId = Option(msgId), detail = Option(detail))
  def withTypeAndDetail(msgType: String, detail: String): MsgEvent =
    MsgEvent(msgType = Option(msgType), detail = Option(detail))

  def apply(msgId: String, msgType: String): MsgEvent =
    MsgEvent(Option(msgId), Option(msgType), None)
  def apply(msgId: String, msgType: String, detail: String): MsgEvent =
    MsgEvent(Option(msgId), Option(msgType), Option(detail))
  def apply(msgId: String, msgType: String, detail: Option[String]): MsgEvent =
    MsgEvent(Option(msgId), Option(msgType), detail)
}

object RoutingEvent {
  val ROUTING_EVENT_ID_ARRIVED = "arrived"
  val ROUTING_EVENT_ID_PROCESSING = "processing"
}

case class RoutingEvent(id: String = RoutingEvent.ROUTING_EVENT_ID_PROCESSING,
                        detail: Option[String] = None,
                        recordedAt: LocalDateTime = LocalDateTime.now(),
                        childEvents: Option[List[ChildEvent]] = None) {
}

case class MsgEvent(msgId: Option[String] = None,
                    msgType: Option[String] = None,
                    detail: Option[String] = None,
                    recordedAt: LocalDateTime = LocalDateTime.now(),
                    childEvents: Option[List[ChildEvent]] = None)

case class RecordRoutingEvent(reqId: String, event: RoutingEvent) extends RecordReqData {
  def withDetailAppended(detail: String): RecordRoutingEvent = {
    copy(event = event.copy(detail = Option(event.detail.map(d => s"$d").getOrElse("") + "\n" + detail)))
  }
}
case class RecordInMsgEvent(reqId: String, event: MsgEvent) extends RecordReqData
case class RecordOutMsgEvent(reqId: String, event: MsgEvent) extends RecordReqData

case class RecordOutMsgDeliveryEvents(msgId: String, events: List[MsgEvent]) extends RecordMsgDeliveryData

case class GetState(requestStateSize: Option[Int] = None,
                    latestFirst: Boolean = true) extends ProgressTrackerMsg

case class RecordedStates(requestStates: List[RequestState], trackingExpiresAt: Instant) extends ProgressTrackerMsg

case class ConfigureTracking(trackForMinutes: Option[Int] = None,
                             stopNow: Boolean=false,
                             startOver: Boolean=false) extends ProgressTrackerMsg

case class TrackingConfigured(message: String) extends ProgressTrackerMsg

case object CheckForPeriodicTask extends ProgressTrackerMsg