package com.evernym.verity.protocol.protocols.updateConfigs.v_0_6

import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{MsgFamily, ParameterName, Parameters, ProtoReceive, ProtocolContextApi, ProtocolDefinition, Scope}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Ctl.InitMsg
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.State.Initial

object UpdateConfigsDefinition extends UpdateConfigsDefTrait {
  override def initialState: State = Initial()
}

trait UpdateConfigsDefTrait extends ProtocolDefinition[UpdateConfigs, Role, ProtoMsg, Event, State, String] {

  val msgFamily: MsgFamily = UpdateConfigsMsgFamily

  override def createInitMsg(params: Parameters): Control = InitMsg()

  override val initParamNames: Set[ParameterName] = Set()

  override def supportedMsgs: ProtoReceive = {
    case _: CtlMsg =>
  }

  override val roles: Set[Role] = Role.roles

  override def create(context: ProtocolContextApi[UpdateConfigs, Role, ProtoMsg, Event, State, String],
                      mw: MetricsWriter): UpdateConfigs = {
    new UpdateConfigs(context)
  }

  override def scope: Scope.ProtocolScope = Scope.Adhoc // Should run on the Self Relationship
}

sealed trait Role
object Role {
  case class Updater() extends Role

  val roles: Set[Role] = Set(Updater())
}