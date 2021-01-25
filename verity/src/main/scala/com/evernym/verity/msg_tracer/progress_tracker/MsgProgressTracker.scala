package com.evernym.verity.msg_tracer.progress_tracker

import akka.pattern.ask
import akka.actor.ActorRef
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.constants.ActorNameConstants.MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME
import com.evernym.verity.Status.DATA_NOT_FOUND
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.{ReqId, ReqMsgId, RespMsgId}
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.msg_tracer.progress_tracker._
import com.evernym.verity.actor.node_singleton.MsgProgressTrackerCache
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.msg_tracer.MsgTraceProvider
import com.evernym.verity.protocol.engine.{MsgId, ProtoDef}
import com.evernym.verity.util.{ReqMsgContext, Util}

import scala.concurrent.Future

trait MsgProgressTracker extends HasActorResponseTimeout { this: MsgTraceProvider =>

  def trackingDomainId: Option[String] = None
  def trackingMyRelationshipId: Option[String] = None

  def domainTrackingId: Option[String] = trackingDomainId.map(Util.sha256Hex(_, length = Option(trackingIdLength)))

  def relTrackingId: Option[String] = {
    if (trackingMyRelationshipId == trackingDomainId) None    //this is self relationship
    else {
      val tid = trackingMyRelationshipId.getOrElse("")
      Option(Util.sha256Hex(tid, length = Option(trackingIdLength)))
    }
  }

  lazy val trackingIdLength = 15

  object MsgProgressTracker {

    def recordEventForTrackingIds(clientIpAddress: String, recordEvent: RecordEvent): Unit = {
      val eventWithIpAddress = recordEvent.copy(clientIpAddress=Option(clientIpAddress))
      checkAndRecord(GLOBAL_REQ_TRACKING_ID, eventWithIpAddress)
      checkAndRecord(clientIpAddress, recordEvent)
      domainTrackingId.foreach(checkAndRecord(_, eventWithIpAddress))
      relTrackingId.foreach(checkAndRecord(_, eventWithIpAddress))
    }

    private def checkAndRecord(trackingId: String, recordEvent: RecordEvent): Unit =
      if (isTrackingStarted(trackingId)) {
        tellToProgressTrackerRegion(trackingId, recordEvent)
      }

    private def isTrackingStarted(trackingId: String): Boolean =
      MsgProgressTrackerCache.isTracked(trackingId)

    def getRecordedRequests(trackingId: String,
                            reqId: Option[String]=None,
                            domainTrackingId: Option[String],
                            relTrackingId: Option[String],
                            withEvents: Option[String]): Future[Any] = {
      if (isTrackingStarted(trackingId)) {
        askToProgressTrackerRegion(trackingId, GetRecordedRequests(reqId, domainTrackingId, relTrackingId, withEvents))
      } else {
        Future.failed(new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("tracking not started")))
      }
    }

    def recordMsgReceivedByHttpEndpoint(endpoint: String)(implicit rmc: ReqMsgContext): Unit = {
      val event = EventReceivedByHttpEndpoint(endpoint=endpoint)
      recordEvent(rmc.clientIpAddressReq, rmc.id, event)
    }

    def recordProtoMsgStatus(protoDef: ProtoDef,
                             pinstId: String,
                             context: String,
                             reqId: String,
                             inMsg: Option[Any]=None,
                             outMsg: Option[Any]=None): Unit = {
      if (isTrackingStarted(pinstId)) {
        val inMsgName = inMsg.map(getMsgName)
        val outMsgName = outMsg.map(getMsgName)
        tellToProgressTrackerRegion(pinstId, RecordEvent(reqId,
          EventParam(EventProtocolMsgProgress(
            context,
            inMsgParam = TrackMsgParam(msgName = inMsgName),
            outMsgParam = TrackMsgParam(msgName = outMsgName),
            protoParam = ProtoParam(Option(pinstId), Option(protoDef.msgFamily.name), Option(protoDef.msgFamily.version))
          ))))
      }

      def getMsgName(msg: Any): String = {
        msg match {
          case s: String => s
          case o: Any    =>
            try {
              protoDef.msgFamily.msgType(o.getClass).msgName
            } catch {
              case _: NoSuchElementException =>
                //TODO: because few old protocol msg families have not declared all control/signal/protocol messages
                //may be we should fix those
                o.getClass.getSimpleName
            }
        }
      }
    }

    def recordGetRouteStarted(context: String, rmc: ReqMsgContext): Unit = {
      val event = EventRouteGetStarted(context)
      recordEvent(rmc.clientIpAddressReq, rmc.id, event)
    }

    def recordGetRouteFinished(context: String, rmc: ReqMsgContext): Unit = {
      val event = EventRouteGetFinished(context)
      recordEvent(rmc.clientIpAddressReq, rmc.id, event)
    }

    def recordMsgReceivedByAgent(additionalContext: String,
                                 trackingParam: TrackingParam = TrackingParam(),
                                 inMsgParam: TrackMsgParam=TrackMsgParam())
                                (implicit rmc: ReqMsgContext): Unit = {
      rmc.clientIpAddress.foreach { cip =>
        val event = EventReceivedByAgent(additionalContext,
          trackingParam = trackingParam,
          inMsgParam = inMsgParam)
        recordEvent(cip, rmc.id, event)
      }
    }

    def recordMsgUnpackedByAgent(additionalContext: String,
                                 inMsgParam: TrackMsgParam=TrackMsgParam())
                                (implicit rmc: ReqMsgContext): Unit = {
      rmc.clientIpAddress.foreach { cip =>
        val event = EventUnpackedByAgent(additionalContext, inMsgParam = inMsgParam)
        recordEvent(cip, rmc.id, event)
      }
    }

    def recordInMsgProcessingStarted(trackingParam: TrackingParam = TrackingParam(),
                                     inMsgParam: TrackMsgParam=TrackMsgParam(),
                                     protoParam: ProtoParam=ProtoParam())
                                    (implicit rmc: ReqMsgContext): Unit = {
      rmc.clientIpAddress.foreach { cip =>

        val event = EventInMsgProcessingStarted(
          trackingParam = trackingParam,
          inMsgParam = inMsgParam,
          protoParam = protoParam)
        recordEvent(cip, rmc.id, event)
      }
    }

    def recordOutMsgPackagingStarted(inMsgParam: TrackMsgParam=TrackMsgParam(),
                                     outMsgParam: TrackMsgParam=TrackMsgParam()): Unit = {
      getAsyncReqContext(inMsgParam.msgId, outMsgParam.msgId).foreach { arc =>
        arc.clientIpAddress.foreach { cip =>
          val event = EventOutMsgPackagingStarted(outMsgId = outMsgParam.msgId)
          recordEvent(cip, arc.reqId, event)
        }
      }
    }

    def recordOutMsgPackagingFinished(inMsgParam: TrackMsgParam=TrackMsgParam(),
                                      outMsgParam: TrackMsgParam=TrackMsgParam()): Unit = {
      getAsyncReqContext(inMsgParam.msgId, outMsgParam.msgId).foreach { arc =>
        arc.clientIpAddress.foreach { cip =>
          val event = EventOutMsgPackagingFinished(outMsgParam = outMsgParam)
          recordEvent(cip, arc.reqId, event)
        }
      }
    }

    def recordMsgSentToNextHop(hop: String, arc: AsyncReqContext): Unit = {
      arc.clientIpAddress.foreach { cip =>
        val event = EventOutMsgSentToNextHop(hop = hop)
        recordEvent(cip, arc.reqId, event)
      }
    }

    def recordMsgSendingFailed(nextHop: String, errorMsg: String, arc: AsyncReqContext): Unit = {
      arc.clientIpAddress.foreach { cip =>
        val event = EventOutMsgSendingFailed(hop = nextHop, errorMsg = errorMsg)
        recordEvent(cip, arc.reqId, event)
      }
    }

    def recordLegacyRespMsgPackagingStarted(respMsgId: Option[RespMsgId]=None)
                                            (implicit rmc: ReqMsgContext): Unit = {
      rmc.clientIpAddress.foreach { cip =>
        val event = EventOutMsgPackagingStarted(outMsgId = respMsgId)
        recordEvent(cip, rmc.id, event)
      }
    }

    def recordLegacyRespMsgPackagingFinished(outMsgParam: TrackMsgParam = TrackMsgParam())
                                            (implicit rmc: ReqMsgContext): Unit = {
      rmc.clientIpAddress.foreach { cip =>
        val event = EventOutMsgPackagingFinished(outMsgParam = outMsgParam)
        recordEvent(cip, rmc.id, event)
      }
    }

    def recordLegacyMsgSentToNextHop(nextHop: String)(implicit rmc: ReqMsgContext): Unit = {
      rmc.clientIpAddress.foreach { cip =>
        val event = EventOutMsgSentToNextHop(hop = nextHop)
        recordEvent(cip, rmc.id, event)
      }
    }

    def recordLegacyMsgSendingFailed(nextHop: String,
                                     errorMsg: String,
                                     replyToMsgId: Option[MsgId]=None)
                                    (implicit rmc: ReqMsgContext): Unit = {
      rmc.clientIpAddress.foreach { cip =>
        val event = EventOutMsgSendingFailed(hop=nextHop, errorMsg=errorMsg)
        recordEvent(cip, rmc.id, event)
      }
    }

    def recordEvent(clientIpAddress: String, reqId: ReqId, event: Any): Unit = {
      recordEventForTrackingIds(clientIpAddress, RecordEvent(reqId, EventParam(event)))
    }

    private def getAsyncReqContext(reqMsgId: Option[ReqMsgId]=None,
                                   respMsgId: Option[RespMsgId]=None): Option[AsyncReqContext] = {
      (reqMsgId, respMsgId) match {
        case (Some(reqMsgId), _               )   => asyncReqContextViaReqMsgId(reqMsgId)
        case (_,              Some(respMsgId) )   => asyncReqContextViaRespMsgId(respMsgId)
        case _                                    => None
      }
    }
  }

  final val GLOBAL_REQ_TRACKING_ID = "global"

  private def askToProgressTrackerRegion(trackingId: String, msg: Any): Future[Any] = {
    region ? ForIdentifier(trackingId, msg)
  }

  private def tellToProgressTrackerRegion(trackingId: String, msg: Any): Unit = {
    region ! ForIdentifier(trackingId, msg)
  }

  private lazy val region: ActorRef = ClusterSharding(system).shardRegion(MSG_PROGRESS_TRACKER_REGION_ACTOR_NAME)
}
