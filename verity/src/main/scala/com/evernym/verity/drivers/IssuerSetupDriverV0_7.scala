package com.evernym.verity.drivers

import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.issuersetup.v_0_7.{WrittenToLedger, NeedsEndorsement, ProblemReport, PublicIdentifier, PublicIdentifierCreated}

import scala.concurrent.ExecutionContext

class IssuerSetupDriverV0_7(cp: ActorDriverGenParam, ec: ExecutionContext) extends ActorDriver(cp, ec) {
  override def signal[A]: SignalHandler[A] = {
    case sig: SignalEnvelope[A] => sendSignalMsg(sig)
  }
}

