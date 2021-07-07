package com.evernym.verity.msg_tracer.resp_time_tracker

import com.evernym.verity.util2.ReqId
import com.evernym.verity.actor.msg_tracer.resp_time_tracker.{NoResp, RespMode}


trait MsgRespTimeTracker
  extends MsgRespTimeRecorderSharded {

  /**
   * there are two implementation of msg tracing recorder provider
   * one is called 'localMsgTraceRecorder', it is limited to local/node scope
   * other one is called 'shardedMsgTraceRecorder' (not used so far) which uses
   * sharded non persistent actors for the same
   * @return
   */
  protected lazy val respTimeMetricsRecorder: MsgRespTimeRecorder = shardedMsgTraceRecorder

  object MsgRespTimeTracker {

    def recordReqReceived(reqId: String, respMode: RespMode=NoResp): Any = {
      respTimeMetricsRecorder.recordReqReceived(reqId, respMode)
    }

    def recordMetrics(reqId: ReqId, msgName: String, nextHop: String): Any = {
      respTimeMetricsRecorder.recordMetrics(reqId, msgName, nextHop)
    }
  }

}

trait MsgRespTimeRecorder {
  def recordReqReceived(reqId: String, respMode: RespMode=NoResp): Any
  def recordMetrics(reqId: ReqId, msgName: String, direction: String): Any
}