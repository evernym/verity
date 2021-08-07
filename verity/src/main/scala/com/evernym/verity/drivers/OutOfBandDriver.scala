package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.outofband.v_1_0.Signal.MoveProtocol

import scala.concurrent.ExecutionContext


class OutOfBandDriver(cp: ActorDriverGenParam, ec: ExecutionContext) extends ActorDriver(cp, ec){

  override def signal[A]: SignalHandler[A] = {
    case se @ SignalEnvelope(_: MoveProtocol, _, _, _, _) =>
      processSignalMsg(se)
      sendSignalMsg(se)
    case sig: SignalEnvelope[A] => sendSignalMsg(sig)
  }
}
