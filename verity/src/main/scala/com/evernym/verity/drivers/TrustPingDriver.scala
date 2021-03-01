package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.trustping.v_1_0.Ctl.SendResponse
import com.evernym.verity.protocol.protocols.trustping.v_1_0.Sig.ReceivedPing

class TrustPingDriver(cp: ActorDriverGenParam)
  extends ActorDriver(cp) {

  override def signal[A]: SignalHandler[A] = {

    case se @ SignalEnvelope(_: ReceivedPing, _, _, _, _) =>
      Some(SendResponse())

    case se @ SignalEnvelope(_, _, _, _, _) =>
      sendSignalMsg(se)
  }
}
