package com.evernym.verity.protocol.protocols.trustping.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.CommonProtoTypes.{Timing => BaseTiming}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProtocolHelpers

class TrustPingProtocol(implicit val ctx: ProtocolContextApi[TrustPingProtocol, Role, Msg, Event, State, String])
  extends Protocol[TrustPingProtocol, Role, Msg, Event, State, String](TrustPingDefinition)
  with ProtocolHelpers[TrustPingProtocol, Role, Msg, Event, State, String]{

  import TrustPingProtocol._

  def handleControl: Control ?=> Any = statefulHandleControl {
    case (_: State.Uninitialized, _, m: Ctl.Init) => apply(Initialized(m.selfId, m.otherId))
    case (_: State.Initialized,   _, m: Ctl.SendPing        ) => sendPing(m)
    case (s: State.ReceivedPing,  _, m: Ctl.SendResponse    ) => sendResponse(s, m)
  }

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = {
    case (s: State.Initialized , _, m: Msg.Ping ) =>
      receivedPing(s, m)
    case (s: State.SentPing, _, m: Msg.Response ) =>
      receivedResponse(s, m)
  }

  def applyEvent: ApplyEvent = {
    applyCommonEvt orElse applySenderEvt orElse applyReceiverEvt
  }

  def applyCommonEvt: ApplyEvent = {
    case (_: State.Uninitialized , _ , e: Initialized ) => (State.Initialized(), initialize(e))
    case (_                      , _ , MyRole(n)      ) => (None, setRole(n))
  }

  def applySenderEvt: ApplyEvent = {
    case (_: State.Initialized, _, e: SentPing)      => State.SentPing(Msg.fromPingEvt(e.ping.get))
    case (_: State.SentPing, _, e: ReceivedResponse) => State.ReceivedResponse(Msg.fromResponseEvt(e.resp.get))
  }

  def applyReceiverEvt: ApplyEvent = {
    case (_: State.Initialized, _, e: ReceivedPing) => State.ReceivedPing(Msg.fromPingEvt(e.ping.get))
    case (_: State.ReceivedPing, _, e: SentResponse) => State.SentResponded(e.resp.map(Msg.fromResponseEvt))
  }

  def receivedPing(s: State.Initialized, m: Msg.Ping): Unit = {
    apply(MyRole(Role.Receiver.roleNum))
    val pingEvt = PingEvt(m.comment.getOrElse(""), m.response_requested.getOrElse(true), Msg.timingToTimingEvt(m.`~timing`))
    apply(ReceivedPing(Some(pingEvt)))
    signal(Sig.ReceivedPing(m))
  }

  def receivedResponse(s: State.SentPing, m: Msg.Response): Unit = {
    val ping = s.msg
    if (!ping.response_requested.getOrElse(true)) {
      logger.warn("Received ping response when not expecting one")
    }

    val respEvt = ResponseEvt(m.comment.getOrElse(""), Msg.timingToTimingEvt(m.`~timing`))
    apply(ReceivedResponse(Some(respEvt)))
    signal(Sig.ReceivedResponse(m))
  }

  def setRole(role: Int): Option[Roster[Role]] = {
    val myRole = Role.numToRole(role)
    val otherAssignment = Role.otherRole(myRole) -> ctx.getRoster.otherId()
    ctx.getRoster.withSelfAssignment(myRole).withAssignmentById(otherAssignment)
  }

  def initialize(p: Initialized): Roster[Role] = {
    ctx.updatedRoster(Seq(InitParamBase(SELF_ID, p.selfIdValue), InitParamBase(OTHER_ID, p.otherIdValue)))
  }

  def sendPing(m: Ctl.SendPing): Unit = {
    apply(MyRole(Role.Sender.roleNum))
    val delay = m.delay_milli match {
      case None => None
      case d: Option[Int] => d match {
          case Some(d) if d < 0 => Some(0)
          case Some(d) if d > MAX_DELAY_MILLI => Some(MAX_DELAY_MILLI)
          case Some(d) => Some(d)
          case _ => None
        }
    }

    val timing = (m.expires_time, delay) match {
      case (None, None) => None
      case (e, d) =>  Some(BaseTiming(expires_time = e, delay_milli = d))
    }

    val pingEvt = PingEvt(m.comment.getOrElse(""), m.response_requested, Msg.timingToTimingEvt(timing))
    apply(SentPing(Some(pingEvt)))

    val ping = Msg.Ping(m.comment, Option(m.response_requested), timing)
    send(ping, Some(Role.Receiver), Some(Role.Sender))
  }

  def sendResponse(s: State.ReceivedPing, m: Ctl.SendResponse): Unit = {
    val d = s.msg.`~timing`
      .flatMap(_.delay_milli)
      .map(_.max(0)) // make sure delay is not negative
      .map(_.min(MAX_DELAY_MILLI)) // make sure delay is not greater than Max Delay
      .getOrElse(0)

    if(d > 0) Thread.sleep(d)

    if(s.msg.response_requested.getOrElse(true)) {
      val respEvt = ResponseEvt(m.comment.getOrElse(""), None)
      val resp = Msg.Response(m.comment, None)
      send(resp)
      apply(SentResponse(Some(respEvt)))
    }
    else {
      apply(SentResponse(None))
    }

    signal(Sig.SentResponse(s.msg.response_requested.getOrElse(true)))
  }
}

object TrustPingProtocol {
  val MAX_EXPIRATION_TIME: String = "9999-12-31T23:59:59+0000"
  val MAX_DELAY_MILLI: Int = 300
}