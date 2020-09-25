package com.evernym.verity.msg_tracer

import java.time.Instant

import akka.actor.ActorSystem
import com.evernym.verity.{ReqId, ReqMsgId, RespMsgId}
import com.evernym.verity.msg_tracer.resp_time_tracker.MsgRespTimeTracker
import com.evernym.verity.msg_tracer.progress_tracker.MsgProgressTracker
import com.evernym.verity.actor.agent.SpanUtil.runWithInternalSpan

/**
 * main purpose of this MsgTraceProvider is to provide a clean interface
 * to be used at different places in the code to store tracing related information
 * (like request id, msg id, resp msg id) and based on these stored information,
 * metrics or progress can be recorded
 */

object MsgTraceProvider {
  final val NEXT_HOP_MY_EDGE_AGENT = "my edge agent"
  final val NEXT_HOP_MY_EDGE_AGENT_SYNC = "my edge agent (synchronous)"
  final val NEXT_HOP_THEIR_ROUTING_SERVICE = "their routing service"
}

trait MsgTraceProvider
  extends MsgRespTimeTracker
    with MsgProgressTracker
    with HasAsyncReqContext {

  def system: ActorSystem

  object MsgTracerProvider {

    def recordMetricsForAsyncRespMsgId(respMsgId: RespMsgId, nextHop: String): Unit = {
      val (matched, _) = asyncReqContext.partition(_._2.respMsgId.contains(respMsgId))
      recordMetricsViaAsyncContext(matched, nextHop)
    }

    def recordMetricsForAsyncReqMsgId(reqMsgId: ReqMsgId, nextHop: String): Unit = {
      val (matched, _) = asyncReqContext.partition(_._1 == reqMsgId)
      recordMetricsViaAsyncContext(matched, nextHop)
    }

    private def recordMetricsViaAsyncContext(reqContexts: Map[ReqMsgId, AsyncReqContext], nextHop: String): Unit = {
      reqContexts.foreach { case (_, reqDetail) =>
        MsgRespTimeTracker.recordMetrics(reqDetail.reqId, reqDetail.msgName, nextHop)
      }
    }
  }

}

trait HasAsyncReqContext {

  protected var asyncReqContext: Map[ReqMsgId, AsyncReqContext] = Map.empty

  /**
   * after how much time record stored in 'asyncReqContext' should be considered as not required
   */
  val maxAliveMillis: Int = 5*60*1000 //5 min

  /**
   * remove stale recorders (to handle unhappy paths where record doesn't gets cleaned up)
   */
  def removeStale(): Unit = {
    runWithInternalSpan("removeStale", "HasAsyncReqContext"){
      val curEpochMillis = Instant.now().toEpochMilli
      val (matched, _) = asyncReqContext.partition(r => (curEpochMillis - r._2.capturedAtEpochMillis) < maxAliveMillis)
      asyncReqContext = matched
    }
  }

  def storeAsyncReqContext(reqMsgId: ReqMsgId, msgName: String, reqId: ReqId, clientIpAddress: Option[String]=None): Unit = {
    removeStale()
    asyncReqContext = asyncReqContext + (reqMsgId -> AsyncReqContext(reqId, msgName, clientIpAddress))
  }

  def asyncReqContextViaReqMsgId(reqMsgId: ReqMsgId, removeMatched: Boolean=false): Option[AsyncReqContext] = {
    runWithInternalSpan("asyncReqContextViaReqMsgId", "HasAsyncReqContext"){
      val (matched, others) = asyncReqContext.partition(_._1 == reqMsgId)
      if (removeMatched) {
        asyncReqContext = others
      }
      matched.values.headOption
    }
  }

  def asyncReqContextViaRespMsgId(respMsgId: RespMsgId, removeMatched: Boolean=false): Option[AsyncReqContext] = {
    runWithInternalSpan("asyncReqContextViaRespMsgId", "HasAsyncReqContext"){
      val (matched, others) = asyncReqContext.partition(r => r._2.respMsgId.contains(respMsgId))
      if (removeMatched) {
        asyncReqContext = others
      }
      matched.values.headOption
    }
  }

  /**
   * stores relationship between request message id and response message id
   * to be used later on
   * @param reqMsgId request msg id
   * @param respMsgId response msg id
   */
  def updateAsyncReqContext(reqMsgId: ReqMsgId, respMsgId: RespMsgId, msgName: Option[String]=None): Unit = {
    runWithInternalSpan("updateAsyncReqContext", "HasAsyncReqContext"){
      val (matched, _) = asyncReqContext.partition(_._1 == reqMsgId)
      matched.foreach { m =>
        val newMsgName = m._2.msgName + msgName.map(mn => s":$mn").getOrElse("")
        val updated = m._2.copy(respMsgId = Option(respMsgId), msgName = newMsgName)
        asyncReqContext = asyncReqContext ++ Map(m._1 -> updated)
      }
    }
  }

  def withReqMsgId(reqMsgId: ReqMsgId, f: AsyncReqContext => Unit): Unit = {
    asyncReqContextViaReqMsgId(reqMsgId, removeMatched = true).foreach { arc =>
      f(arc)
    }
  }

  def withRespMsgId(respMsgId: RespMsgId, f: AsyncReqContext => Unit): Unit = {
    asyncReqContextViaRespMsgId(respMsgId, removeMatched = true).foreach { arc =>
      f(arc)
    }
  }

  case class AsyncReqContext(reqId: ReqId,
                             msgName: String,
                             clientIpAddress: Option[String]=None,
                             respMsgId: Option[RespMsgId]=None,
                             capturedAtEpochMillis: Long = Instant.now().toEpochMilli)
}