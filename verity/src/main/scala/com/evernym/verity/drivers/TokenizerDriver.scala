package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler

import scala.concurrent.ExecutionContext

class TokenizerDriver(cp: ActorDriverGenParam, ec: ExecutionContext)
  extends ActorDriver(cp, ec) {

  override def signal[A]: SignalHandler[A] = ???
}
