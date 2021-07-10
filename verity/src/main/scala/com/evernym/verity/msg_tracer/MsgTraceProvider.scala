package com.evernym.verity.msg_tracer

import java.time.Instant

import akka.actor.ActorSystem
import com.evernym.verity.util2.{ReqId, RespMsgId}
import com.evernym.verity.msg_tracer.resp_time_tracker.MsgRespTimeTracker

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
    with HasAsyncReqContext {

  def system: ActorSystem

  object MsgTracerProvider {

    def recordMetricsForAsyncRespMsgId(respMsgId: RespMsgId, nextHop: String): Unit = {
      if (asyncReqContext.exists(_.respMsgId.contains(respMsgId)))
        recordMetricsViaAsyncContext(nextHop)
    }

    def recordMetricsForAsyncReq(nextHop: String): Unit = {
      if (asyncReqContext.isDefined)
        recordMetricsViaAsyncContext(nextHop)
    }

    private def recordMetricsViaAsyncContext(nextHop: String): Unit = {
      asyncReqContext.foreach { reqContext =>
        MsgRespTimeTracker.recordMetrics(reqContext.reqId, reqContext.msgName, nextHop)
      }
    }
  }

}

trait HasAsyncReqContext {

  protected var asyncReqContext: Option[AsyncReqContext] = None

  protected def storeAsyncReqContext(msgName: String, reqId: ReqId, clientIpAddress: Option[String]=None): Unit = {
    asyncReqContext = Option(AsyncReqContext(reqId, msgName, clientIpAddress))
  }

  protected def asyncReqContextViaRespMsgId(respMsgId: RespMsgId): Option[AsyncReqContext] = {
    asyncReqContext.find(r => r.respMsgId.contains(respMsgId))
  }

  /**
   * stores relationship between request message id and response message id
   * to be used later on
   * @param respMsgId response msg id
   */
  protected def updateAsyncReqContext(respMsgId: RespMsgId, msgName: Option[String]=None): Unit = {
    val newMsgName = asyncReqContext.map(_.msgName) + msgName.map(mn => s":$mn").getOrElse("")
    val updated = asyncReqContext.map(_.copy(respMsgId = Option(respMsgId), msgName = newMsgName))
    asyncReqContext = updated
  }

  protected def withReqMsgId(f: AsyncReqContext => Unit): Unit = {
    asyncReqContext.foreach { arc =>
      f(arc)
    }
  }

  protected def withRespMsgId(respMsgId: RespMsgId, f: AsyncReqContext => Unit): Unit = {
    asyncReqContextViaRespMsgId(respMsgId).foreach { arc =>
      f(arc)
    }
  }

  case class AsyncReqContext(reqId: ReqId,
                             msgName: String,
                             clientIpAddress: Option[String]=None,
                             respMsgId: Option[RespMsgId]=None,
                             capturedAtEpochMillis: Long = Instant.now().toEpochMilli)
}