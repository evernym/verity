package com.evernym.verity.protocol.protocols.updateConfigs.v_0_6

import com.evernym.verity.constants.Constants.{LOGO_URL_KEY, NAME_KEY}
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.{MsgBase, MsgFamily, MsgFamilyName, MsgFamilyQualifier, MsgFamilyVersion, MsgName}

object UpdateConfigsMsgFamily extends MsgFamily {
  override val qualifier: MsgFamilyQualifier = MsgFamily.EVERNYM_QUALIFIER
  override val name: MsgFamilyName = "update-configs"
  override val version: MsgFamilyVersion = "0.6"

  override protected val protocolMsgs: Map[MsgName, Class[_]] = Map.empty

  override protected val controlMsgs: Map[MsgName, Class[_ <: MsgBase]] = Map(
    "update"           -> classOf[Ctl.Update],
    "get-status"       -> classOf[Ctl.GetStatus],
    "config-data"      -> classOf[Ctl.SendConfig]
  )
  override protected val signalMsgs: Map[Class[_], MsgName] = Map(
    classOf[Sig.ConfigResult] -> "status-report",
    classOf[Sig.UpdateConfig] -> "update-config",
    classOf[Sig.GetConfigs]   -> "get-configs"
  )
}


sealed trait SigMsg extends MsgBase
object Sig {
  case class UpdateConfig(configs: Set[Config]) extends SigMsg
  case class ConfigResult(configs: Set[Config]) extends SigMsg
  case class GetConfigs(names: Set[String]) extends SigMsg
}

/**
 * Control Messages
 */
trait CtlMsg extends Control with MsgBase
object Ctl {
  case class InitMsg() extends CtlMsg
  case class Update(configs: Set[Config]) extends CtlMsg
  case class GetStatus() extends CtlMsg
  case class SendConfig(configs: Set[Config]) extends CtlMsg {
    def name : String = getConfigByKey(NAME_KEY)
    def logoUrl : String = getConfigByKey(LOGO_URL_KEY)

    private def getConfigByKey(key: String) : String =
      configs.filter(cd => cd.name.equals(key)).map(cd => cd.value).lastOption.orNull
  }
}

case class Config(name: String, value: String)

trait ProtoMsg extends MsgBase