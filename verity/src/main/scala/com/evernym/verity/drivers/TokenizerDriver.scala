package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler

class TokenizerDriver(cp: ActorDriverGenParam)
  extends ActorDriver(cp) {

  override def signal[A]: SignalHandler[A] = ???
}
