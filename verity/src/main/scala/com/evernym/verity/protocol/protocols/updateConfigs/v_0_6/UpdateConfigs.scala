package com.evernym.verity.protocol.protocols.updateConfigs.v_0_6

import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.context.ProtocolContextApi
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.ProtocolHelpers.noHandleProtoMsg
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Ctl.{GetStatus, InitMsg, SendConfig, Update}
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Sig.{ConfigResult, UpdateConfig}

class UpdateConfigs(val ctx: ProtocolContextApi[UpdateConfigs, Role, ProtoMsg, Event, State, String])
  extends Protocol[UpdateConfigs, Role, ProtoMsg, Event, State, String](UpdateConfigsDefinition) {

  def handleControl: Control ?=> Any = {
    case _: InitMsg    =>
    case m: Update     => handleUpdateRequest(m)
    case _: GetStatus  => handleGetStatus()
    case c: SendConfig => handleSendConfig(c)
  }

  def handleGetStatus(): Unit = ctx.signal(Sig.GetConfigs(Set(NAME_KEY, LOGO_URL_KEY)))

  def handleSendConfig(c: SendConfig): Unit = {
    val configs = c.configs.map(cd => Config(cd.name, cd.value))
    ctx.signal(ConfigResult(configs))
  }

  def handleUpdateRequest(m: Update): Unit = {
    ctx.signal(UpdateConfig(m.configs))
    ctx.signal(Sig.GetConfigs(Set(NAME_KEY, LOGO_URL_KEY)))
  }

  override def applyEvent: ApplyEvent = ???

  override def handleProtoMsg: (State, Option[Role], ProtoMsg) ?=> Any = noHandleProtoMsg()
}
