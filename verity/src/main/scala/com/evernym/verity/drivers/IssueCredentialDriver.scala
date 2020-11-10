package com.evernym.verity.drivers

import com.evernym.verity.protocol.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.SignalMsg.ShortenInvite

class IssueCredentialDriver(cp: ActorDriverGenParam) extends ActorDriver(cp) {

  override def signal[A]: SignalHandler[A] = {
    case se @ SignalEnvelope(_: ShortenInvite, _, _, _, _) => processSignalMsg(se)
    case sig: SignalEnvelope[A] => sendSignalMsg(sig)
  }
}
