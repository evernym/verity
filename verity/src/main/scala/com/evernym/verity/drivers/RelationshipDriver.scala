package com.evernym.verity.drivers

import com.evernym.verity.protocol.actor._
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.{CreatePairwiseKey, SendSMSInvite, ShortenInvite}


class RelationshipDriver(cp: ActorDriverGenParam)
  extends ActorDriver(cp) {

  override def signal[A]: SignalHandler[A] = {
    case se @ SignalEnvelope(_: CreatePairwiseKey, _, _, _, _) =>
      processSignalMsg(se)

    case se @ SignalEnvelope(_: ShortenInvite, _, _, _, _) =>
      processSignalMsg(se)

    case se @ SignalEnvelope(_: SendSMSInvite, _, _, _, _) =>
      processSignalMsg(se)

    case se: SignalEnvelope[A] =>
      sendSignalMsg(se)
  }

}
