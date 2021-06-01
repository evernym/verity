package com.evernym.verity.protocol.protocols

import com.evernym.verity.constants.Constants.UNKNOWN_OTHER_ID
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.util.OptionUtil
import com.typesafe.scalalogging.Logger

trait ProtocolHelpers[P,R,M,E,S,I] {
  type Context = ProtocolContextApi[P, R, M, E, S, String]

  def apply(event: E)
           (implicit ctx: Context): Unit = {
    ctx.apply(event)
  }

  def send(msg: M, toRole: Option[R]=None, fromRole: Option[R]=None)
          (implicit ctx: Context): Unit = {
    ctx.send(msg, toRole, fromRole)
  }

  def signal(signalMsg: Any)
            (implicit ctx: Context): Unit = {
    ctx.signal(signalMsg)
  }

  def setRole(myRole: R, theirRole: R)
             (implicit ctx: Context): Roster[R] = {
    val r = ctx.getRoster.withSelfAssignment(myRole)
    if (r.hasOther) {
      r.withAssignmentById(theirRole -> r.otherId())
    }
    else r
  }

  def setupParticipantIds(selfId: String, otherId: String)
              (implicit ctx: Context): Roster[R] = {
    Option(ctx.getRoster)
      .map { r =>
        OptionUtil.blankOption(selfId)
        .map(
          r.withParticipant(_, true)
        )
        .getOrElse(r)
      }
      .map { r =>
        OptionUtil.blankOption(otherId)
        .filterNot(_ == UNKNOWN_OTHER_ID) // We do not want to set the UNKNOWN_OTHER_ID
        .map(
          r.withParticipant(_)
        )
        .getOrElse(r)
      }
      .getOrElse(ctx.getRoster)
  }

  def statefulHandleControl(pf: (S, Option[R], Control) ?=> Any)
                           (implicit ctx: Context): Control ?=> Any = {
    case c: Control if pf.isDefinedAt((ctx.getState, ctx.getRoster.selfRole, c)) =>
      pf((ctx.getState, ctx.getRoster.selfRole, c))
  }

  def logger(implicit ctx: Context): Logger = {
    ctx.logger
  }

}

object ProtocolHelpers {
  val noHandleProtoMsg = "This protocol don't have protocol messages! getting here should not be passable"

  def noHandleProtoMsg[S, R, M](customMsg: String = noHandleProtoMsg): (S, Option[R], M) ?=> Any = {
    case _ => throw new RuntimeException(noHandleProtoMsg)
  }

  def defineSelf[R](roster: Roster[R], id: ParticipantId, role: R): Roster[R] = {
    roster
      .withParticipant(id, isSelf = true)
      .withSelfAssignment(role)
  }
}