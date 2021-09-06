package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Sig.{ConfigResult, GetConfigs, UpdateConfig}

import scala.concurrent.ExecutionContext

class UpdateConfigsDriver(cp: ActorDriverGenParam, ec: ExecutionContext) extends ActorDriver(cp, ec) {

  override def signal[A]: SignalHandler[A] = {

    case sig@SignalEnvelope(_: GetConfigs, _, _, _, _)  => processSignalMsg(sig)

    case sig@SignalEnvelope(_: UpdateConfig, _, _, _, _) => processSignalMsg(sig)

    //these signals will be sent to edge agent
    case sig@SignalEnvelope(_: ConfigResult, _, _, _, _) => sendSignalMsg(sig)
  }
}
