package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{ProblemReport, PublicIdentifier, PublicIdentifierCreated}

import scala.concurrent.ExecutionContext


class IssuerSetupDriver(cp: ActorDriverGenParam, ec: ExecutionContext) extends ActorDriver(cp, ec) {
  override def signal[A]: SignalHandler[A] = {
    case sig @ SignalEnvelope(_: PublicIdentifierCreated, _, _, _, _) =>
      processSignalMsg(sig)
      sendSignalMsg(sig)

    case sig @ SignalEnvelope(_: PublicIdentifier | _: ProblemReport, _, _, _, _) =>
      sendSignalMsg(sig)
  }

}

