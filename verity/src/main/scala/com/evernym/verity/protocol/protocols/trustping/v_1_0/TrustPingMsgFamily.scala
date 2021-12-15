package com.evernym.verity.protocol.protocols.trustping.v_1_0

import com.evernym.verity.did.DidStr
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.{MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.trustping.v_1_0.Ctl.Init
import com.evernym.verity.util.OptionUtil.emptyOption

object TrustPingFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.COMMUNITY_QUALIFIER
  override val name: MsgFamilyName = "trust_ping"
  override val version: MsgFamilyVersion = "1.0"

  override protected val protocolMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "ping"           -> classOf[Msg.Ping],
    "ping_response"  -> classOf[Msg.Response]
  )

  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "Init"           -> classOf[Init],
    "send-ping"      -> classOf[Ctl.SendPing],
    "send-response"  -> classOf[Ctl.SendResponse],
  )

  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Sig.ReceivedPing]        -> "received-ping",
    classOf[Sig.SentResponse]        -> "sent-response",
    classOf[Sig.ReceivedResponse]    -> "received-response"
  )
}


// Messages
sealed trait Msg extends MsgBase

object Msg {
  def timingEvtToTiming(t: Timing): BaseTiming = {
    BaseTiming(
      emptyOption(t.expiresTime),
      emptyOption(t.inTime),
      emptyOption(t.outTime),
      emptyOption(t.staleTime),
      Some(t.delayMilli),
      emptyOption(t.waitUntilTime)
    )
  }

  def timingToTimingEvt(t: Option[BaseTiming]): Option[Timing] = t.map(timingToTimingEvt)

  def timingToTimingEvt(t: BaseTiming): Timing = {
    Timing(
      expiresTime = t.expires_time.getOrElse(""),
      inTime = t.in_time.getOrElse(""),
      outTime = t.out_time.getOrElse(""),
      staleTime = t.stale_time.getOrElse(""),
      delayMilli = t.delay_milli.getOrElse(0),
      waitUntilTime = t.wait_until_time.getOrElse(""),
    )
  }

  def fromPingEvt(evt: PingEvt): Ping = {
    Ping(emptyOption(evt.comment), Option(evt.responseRequested), evt.timing.map(timingEvtToTiming))
  }

  def fromResponseEvt(evt: ResponseEvt): Response = {
    Response(emptyOption(evt.comment), evt.timing.map(timingEvtToTiming))
  }

  case class Ping(comment: Option[String],
                  response_requested: Option[Boolean],
                  `~timing`: Option[BaseTiming]=None
                 ) extends Msg {

  }

  case class Response(comment: Option[String],
                      `~timing`: Option[BaseTiming]
                     ) extends Msg
}

// Control Messages
sealed trait Ctl extends Control with MsgBase

object Ctl {
  case class Init(selfId: ParameterValue, otherId: ParameterValue) extends Ctl

  case class SendPing(comment: Option[String] = None,
                      response_requested: Boolean,
                      expires_time: Option[String] = None,
                      delay_milli: Option[Int] = None) extends Ctl

  case class SendResponse(comment: Option[String] = None) extends Ctl
}

// Signal Messages
sealed trait Signal

object Sig {
  case class ReceivedPing(ping: Msg.Ping) extends Signal
  case class SentResponse(responseSent: Boolean, relationship: DidStr) extends Signal
  case class ReceivedResponse(resp: Msg.Response) extends Signal
}