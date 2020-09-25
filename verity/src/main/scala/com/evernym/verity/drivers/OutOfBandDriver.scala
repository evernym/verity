package com.evernym.verity.drivers

import com.evernym.verity.protocol.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope


class OutOfBandDriver(cp: ActorDriverGenParam) extends ActorDriver(cp){

  override def signal[A]: SignalHandler[A] = {
    case sig: SignalEnvelope[A] => sendSignalMsg(sig)
  }
}
