package com.evernym.verity.protocol.protocols.trustping.v_1_0

import com.evernym.verity.constants.InitParamConstants._
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.asyncapi.AccessRight
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.engine._

sealed trait Role

object Role {

  def numToRole: Int ?=> Role = {
    case 0 => Sender
    case 1 => Receiver
  }

  def otherRole: Role ?=> Role = {
    case Sender => Receiver
    case Receiver => Sender
  }

  case object Sender extends Role {
    def roleNum = 0
  }

  case object Receiver extends Role {
    def roleNum = 1
  }

}

object TrustPingDefinition extends ProtocolDefinition[TrustPingProtocol, Role, Msg, Event, State, String] {
  val msgFamily: MsgFamily = TrustPingFamily

  override val initParamNames: Set[ParameterName] = Set(SELF_ID, OTHER_ID)

  override val roles: Set[Role] = Set(Role.Sender, Role.Receiver)

  override val requiredAccess: Set[AccessRight] = Set()

  override def createInitMsg(p: Parameters): Control = Ctl.Init(p.paramValueRequired(SELF_ID), p.paramValueRequired(OTHER_ID))

  override def create(context: ProtocolContextApi[TrustPingProtocol, Role, Msg, Event, State, String],
                      mw: MetricsWriter):
  TrustPingProtocol = {
    new TrustPingProtocol()(context)
  }

  def initialState: State = State.Uninitialized()
}
