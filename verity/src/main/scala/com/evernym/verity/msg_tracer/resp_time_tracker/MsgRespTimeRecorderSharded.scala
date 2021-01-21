package com.evernym.verity.msg_tracer.resp_time_tracker

import akka.actor.ActorRef
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.constants.ActorNameConstants.MSG_TRACER_REGION_ACTOR_NAME
import com.evernym.verity.ReqId
import com.evernym.verity.actor.ForIdentifier
import com.evernym.verity.actor.msg_tracer.resp_time_tracker.{CaptureMetrics, NoResp, ReqReceived, RespMode}
import com.evernym.verity.msg_tracer.MsgTraceProvider

/**
 * it uses akka cluster sharding to distribute in memory msg tracing state
 * to different actors sharded across the cluster
 */
trait MsgRespTimeRecorderSharded { this : MsgTraceProvider =>

  def msgSender: Option[ActorRef] = None

  protected lazy val shardedMsgTraceRecorder: MsgRespTimeRecorder = new MsgRespTimeRecorder {

    protected def sendToMsgTracerRegion(reqId: ReqId, msg: Any): Unit = {
      val sndr = msgSender.getOrElse(ActorRef.noSender)
      msgTracerRegion.tell(ForIdentifier(reqId, msg), sndr)
    }

    override def recordReqReceived(reqId: String, respMode: RespMode=NoResp): Unit = {
      sendToMsgTracerRegion(reqId, ReqReceived(respMode))
    }

    override def recordMetrics(reqId: ReqId, msgName: String, nextHop: String): Unit = {
      sendToMsgTracerRegion(reqId, CaptureMetrics(msgName, nextHop))
    }

    private lazy val msgTracerRegion: ActorRef =
      ClusterSharding(system).shardRegion(MSG_TRACER_REGION_ACTOR_NAME)
  }
}
