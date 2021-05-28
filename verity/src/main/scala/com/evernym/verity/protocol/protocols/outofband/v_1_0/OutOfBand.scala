package com.evernym.verity.protocol.protocols.outofband.v_1_0

import akka.http.scaladsl.model.Uri
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Ctl.Reuse
import com.evernym.verity.protocol.protocols.outofband.v_1_0.InviteUtil.{isThreadedInviteId, parseThreadedInviteId}
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Signal.MoveProtocol
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

class OutOfBand(val ctx: ProtocolContextApi[OutOfBand, Role, Msg, OutOfBandEvent, State, String])
  extends Protocol[OutOfBand, Role, Msg, OutOfBandEvent, State, String](OutOfBandDef){

  def handleControl: Control ?=> Any = {
    case c: Control => mainHandleControl(ctx.getState, c)
  }

  // Control Message Handlers
  def mainHandleControl: (State, Control) ?=> Unit = {
    case (_: State.Uninitialized, m: Ctl.Init       ) =>
      ctx.apply(Initialized(m.params.initParams.map(p => InitParam(p.name, p.value)).toSeq))
    case (_: State.Initialized, m: Reuse            ) => handleReuse(m)
  }

  // Protocol Msg Handlers
  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = {
    case (_: State.Initialized, _ , m: Msg.HandshakeReuse)                      => handleHandshakeReuse(m)
    case (_: State.ConnectionReuseRequested, _ , m: Msg.HandshakeReuseAccepted) => receivedHandshakeReuseAccepted(m)
  }


  def handleHandshakeReuse(m: Msg.HandshakeReuse): Any = {
    if (m.`~thread` == null || m.`~thread`.pthid == null) {
      ctx.send(Msg.buildProblemReport(s"Message $m has invalid '~thread` field", "invalid-reuse-message"))
    }
    else {
      ctx.apply(ConnectionReuseRequested())
      val resp = Msg.HandshakeReuseAccepted(m.`~thread`)
      ctx.send(resp)
      ctx.signal(Signal.ConnectionReused(m.`~thread`, ctx.getRoster.selfId_!))
      handleThreadInviteId(m.`~thread`.pthid)
      ctx.apply(ConnectionReused())
    }
  }

  def receivedHandshakeReuseAccepted(m: Msg.HandshakeReuseAccepted): Any = {
    ctx.apply(ConnectionReused())
    ctx.signal(Signal.ConnectionReused(m.`~thread`, ctx.getRoster.selfId_!))
  }

  def handleReuse(m: Reuse): Unit = {
    try {
      val decoded = Base64Util.getBase64UrlDecoded(Uri(m.inviteUrl).query().getOrElse("oob", ""))
      val invite = new JSONObject(new String(decoded))
      val reuseMsg = Msg.HandshakeReuse(Thread(null, Option(invite.getString("@id"))))
      ctx.send(reuseMsg)
      ctx.apply(ConnectionReuseRequested())
    } catch {
      case e: Exception =>
        ctx.logger.warn(s"Reuse handling failed: ${e.getMessage}")
        ctx.signal(Signal.buildProblemReport(s"Message $m has invalid 'inviteUrl` field",
        "invalid-reuse-message"))
    }
  }

  def handleThreadInviteId(id: Option[String]): Unit = {
    id.foreach{ id =>
      if(isThreadedInviteId(id)) {
        parseThreadedInviteId(id)
          .foreach{ p =>
            ctx.signal(MoveProtocol(p.protoRefStr, p.relationshipId, ctx.getRoster.selfId_!, p.threadId))
          }
      }
    }
  }


  override def applyEvent: ApplyEvent = {
    case (_: State.Uninitialized             , _ , e: Initialized          ) =>
      val paramMap = e.params map { p => InitParamBase(p.name, p.value) }
      (State.Initialized(), initialize(paramMap))
    case (_: State.Initialized               , _ , _: ConnectionReuseRequested  ) =>
      (State.ConnectionReuseRequested(), ctx.getRoster)
    case (_: State.ConnectionReuseRequested  , _ , _: ConnectionReused  )         =>
      (State.ConnectionReused(), ctx.getRoster)
  }

  def initialize( paramMap: Seq[InitParamBase]): Roster[Role] = {
    ctx.updatedRoster(paramMap)
  }


}

sealed trait Role
object Role {
  case object Inviter extends Role
  case object Invitee extends Role
}

trait OutOfBandEvent

