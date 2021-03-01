package com.evernym.verity.drivers

import com.evernym.verity.actor.ConnectionStatusUpdated
import com.evernym.verity.agentmsg.msgfamily.pairwise.{AcceptedInviteAnswerMsg_0_6, ConnReqRespMsg_MFV_0_6, KeyCreatedRespMsg_MFV_0_6, RedirectedInviteAnswerMsg_0_6}
import com.evernym.verity.protocol.container.actor.{ActorDriver, ActorDriverGenParam}
import com.evernym.verity.protocol.engine.Driver.SignalHandler
import com.evernym.verity.protocol.engine.SignalEnvelope
import com.evernym.verity.protocol.protocols.connecting.common.{ConnReqReceived, LegacyConnectingSignal, NotifyUserViaPushNotif, SendMsgToRegisteredEndpoint, StatusReport}

/**
 * this is used for legacy connecting 0.5 and 0.6 protocol
 * @param cp: an ActorDriverConstructionParameter
 */
class ConnectingDriver (cp: ActorDriverGenParam) extends ActorDriver(cp) {
  /**
    * Takes a SignalEnvelope and returns an optional Control message.
    *
    * A control message can always be sent later, in which case returning
    * a None is appropriate.
    *
    * @return an optional Control message
    */
  override def signal[A]: SignalHandler[A] = {

    //for now, all below mentioned signals are needs to be sent to original msg forwarder (either user agent or user agent pairwise)
    case sig @ SignalEnvelope(
      _: ConnReqReceived |
      _: ConnectionStatusUpdated |
      _: NotifyUserViaPushNotif |
      _: SendMsgToRegisteredEndpoint |
      _: LegacyConnectingSignal,
      _,_,_,_) => processSignalMsg(sig)

    //these signals will be sent to edge agent
    case sig @ SignalEnvelope(
      _: ConnReqRespMsg_MFV_0_6 |
      _: KeyCreatedRespMsg_MFV_0_6 |
      _: AcceptedInviteAnswerMsg_0_6 |
      _: RedirectedInviteAnswerMsg_0_6 |
      _: StatusReport,
      _, _, _, _) => sendSignalMsg(sig)
  }
}
