package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler

import scala.concurrent.ExecutionContext

//TODO: may be we should remove this as it is not being used by the Tokenizer protocol?
class TokenizerDriver(cp: ActorDriverGenParam, ec: ExecutionContext)
  extends ActorDriver(cp, ec) {

  override def signal[A]: SignalHandler[A] = ???
}
