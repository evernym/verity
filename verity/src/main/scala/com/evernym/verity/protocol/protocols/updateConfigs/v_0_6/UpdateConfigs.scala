package com.evernym.verity.protocol.protocols.updateConfigs.v_0_6

import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.agent.user.GetConfigs
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.util.?=>
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProtocolHelpers.noHandleProtoMsg
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.State.Initial


object UpdateConfigsMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "update-configs"
  override val version: MsgFamilyVersion = "0.6"

  override protected val protocolMsgs: Map[MsgName, Class[_]] = Map.empty

  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "update"           -> classOf[Update],
    "get-status"       -> classOf[GetStatus],
    "config-data"      -> classOf[SendConfig]
  )
  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[ConfigResult] -> "status-report",
    classOf[UpdateConfig] -> "update-config",
    classOf[GetConfigs]   -> "get-configs"
  )
}

trait Event

sealed trait State
object State {
  case class Initial() extends State
}

object UpdateConfigsDefinition extends UpdateConfigsDefTrait {
  override def initialState: State = Initial()
}

trait UpdateConfigsDefTrait extends ProtocolDefinition[UpdateConfigs, Role, Msg, Event, State, String] {

  val msgFamily: MsgFamily = UpdateConfigsMsgFamily

  override def createInitMsg(params: Parameters): Control = InitMsg()

  override val initParamNames: Set[ParameterName] = Set()

  override def supportedMsgs: ProtoReceive = {
    case _: ConfigsControl =>
  }

  override val roles: Set[Role] = Role.roles

  override def create(context: ProtocolContextApi[UpdateConfigs, Role, Msg, Event, State, String]): UpdateConfigs = {
    new UpdateConfigs(context)
  }

  override def scope: Scope.ProtocolScope = Scope.Adhoc // Should run on the Self Relationship
}

sealed trait Role
object Role {
  case class Updater() extends Role

  val roles: Set[Role] = Set(Updater())
}

sealed trait Msg extends MsgBase{
  val msgFamily = ""
}

sealed trait SignalMsg extends MsgBase

case class Config(name: String, value: String)
case class UpdateConfig(configs: Set[Config]) extends SignalMsg
case class ConfigResult(configs: Set[Config]) extends SignalMsg

/**
  * Control Messages
  */
trait ConfigsControl extends Control with MsgBase
case class Update(configs: Set[Config]) extends Msg with ConfigsControl
case class InitMsg() extends ConfigsControl
case class GetStatus() extends Msg with ConfigsControl

case class SendConfig(configs: Set[Config]) extends Msg with ConfigsControl with SignalMsg {
  def name : String = getConfigByKey(NAME_KEY)
  def logoUrl : String = getConfigByKey(LOGO_URL_KEY)

  private def getConfigByKey(key: String) : String =
    configs.filter(cd => cd.name.equals(key)).map(cd => cd.value).lastOption.orNull
}

class UpdateConfigs(val ctx: ProtocolContextApi[UpdateConfigs, Role, Msg, Event, State, String])
  extends Protocol[UpdateConfigs, Role, Msg, Event, State, String](UpdateConfigsDefinition) {

  override def applyEvent: ApplyEvent = ???

  override def handleProtoMsg: (State, Option[Role], Msg) ?=> Any = noHandleProtoMsg()

  def handleSendConfig(c: SendConfig): Unit = {
    val configs = c.configs.map(cd => Config(cd.name, cd.value))
    ctx.signal(ConfigResult(configs))
  }

  def handleControl: Control ?=> Any = {
    case _: InitMsg    =>
    case m: Update     => handleUpdateRequest(m)
    case _: GetStatus  => handleGetStatus()
    case c: SendConfig => handleSendConfig(c)
  }

  def handleGetStatus(): Unit = ctx.signal(GetConfigs(Set(NAME_KEY, LOGO_URL_KEY)))

  def handleUpdateRequest(m: Update): Unit = {
    ctx.signal(UpdateConfig(m.configs))
    ctx.signal(GetConfigs(Set(NAME_KEY, LOGO_URL_KEY)))
  }

}
