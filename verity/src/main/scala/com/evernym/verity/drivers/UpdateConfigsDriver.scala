package com.evernym.verity.drivers


import com.evernym.verity.actor.agent.user.GetConfigs
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigs
import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Sig.{ConfigResult, UpdateConfig}

import scala.concurrent.ExecutionContext

class UpdateConfigsDriver(cp: ActorDriverGenParam, ec: ExecutionContext) extends ActorDriver(cp, ec) {

  override def signal[A]: SignalHandler[A] = {

    case sig@SignalEnvelope(_: GetConfigs, _, _, _, _)  => processSignalMsg(sig)

    case sig@SignalEnvelope(uc: UpdateConfig, _, _, _, _) =>
      val ucn = UpdateConfigs(uc.configs.map(cd => ConfigDetail(cd.name, cd.value)))
      processSignalMsg(sig.copy(signalMsg = ucn))

    //these signals will be sent to edge agent
    case sig@SignalEnvelope(_: ConfigResult, _, _, _, _) => sendSignalMsg(sig)
  }
}
