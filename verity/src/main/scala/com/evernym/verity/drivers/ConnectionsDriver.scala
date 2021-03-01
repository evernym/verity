package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{SetupTheirDidDoc, UpdateTheirDid}

/**
 * this is used for connections 1.0 protocol
 * @param cp: an ActorDriverConstructionParameter
 */
class ConnectionsDriver(cp: ActorDriverGenParam)
  extends ActorDriver(cp) {

  override def signal[A]: SignalHandler[A] = {

    //This case is only valid for those signal messages which we want to send back to the controller

    case se @ SignalEnvelope(_: SetupTheirDidDoc, _, _, _, _) =>
      processSignalMsg(se)

    case se @ SignalEnvelope(_: UpdateTheirDid, _, _, _, _) =>
      processSignalMsg(se)

    case sig: SignalEnvelope[A] =>
      sendSignalMsg(sig)
  }

}


