package com.evernym.verity.actor.msg_tracer.resp_time_tracker

import java.time.Instant

import akka.actor.Props
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.actor.base.BaseNonPersistentActor
import com.evernym.verity.actor.base.Done
import com.evernym.verity.config.AppConfig
import com.evernym.verity.msg_tracer.resp_time_tracker.MsgRespTimeMetricsRecorder

import scala.concurrent.duration._

object MsgTracer {
  def props(appConfig: AppConfig): Props = Props(classOf[MsgTracer], appConfig)
}

/**
 * msg tracer sharded actor
 * @param appConfig
 */
class MsgTracer(val appConfig: AppConfig) extends BaseNonPersistentActor {

  val msgTracingMetricsRecorder = new MsgRespTimeMetricsRecorder(appConfig)

  override def receiveCmd: Receive = {
    case mtm: MsgTracerActorMsg =>
      val resp = msgTracingMetricsRecorder.execute(mtm)
      if (msgTracingMetricsRecorder.isSendBackResp) {
        resp match {
          case () => sender ! Done      //if no explicit response is sent from msg handler, send a Done as a default
          case r  => sender ! r
        }
      }
  }

  //after 5 min of inactivity, it should kill itself
  //assumption behind this is that no request should/will take more than 5 min in processing
  context.setReceiveTimeout(300.seconds)
}


trait RespMode
case object NoResp extends RespMode
case object SendBackResp extends RespMode

//cmd
trait MsgTracerActorMsg extends ActorMessage
case class MetricTag(name: String, value: Any)

case class ReqReceived(respMode: RespMode = NoResp, atEpochMillis: Long = Instant.now().toEpochMilli) extends MsgTracerActorMsg
case class CaptureMetrics(msgName: String, nextHop: String, atEpochMillis: Long = Instant.now().toEpochMilli) extends MsgTracerActorMsg
