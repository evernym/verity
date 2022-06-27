package com.evernym.verity.protocol.protocols.issuersetup.v_0_7

import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.{Driver, SignalEnvelope}
import com.evernym.verity.testkit.TestWallet

//NOTE: this driver will be only used during basic spec (not during actor related spec)
//TODO: this driver is not yet fully implemented/tested
class IssuerSetupBasicDriver(testWallet: TestWallet)extends Driver {
  override def signal[A]: SignalHandler[A] = {

    case SignalEnvelope(_: PublicIdentifierCreated, _, _, _, _) =>
      //TODO: add required implementation
      None

    case SignalEnvelope(_: PublicIdentifier, _, _, _, _) =>
      //TODO: add required implementation
      None

    case SignalEnvelope(_: ProblemReport, _, _, _, _) =>
      //TODO: add required implementation
      None
  }
}
