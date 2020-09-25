package com.evernym.verity.actor.agent.agency

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgFromDriver}
import com.evernym.verity.protocol.protocols.connecting.common.LegacyConnectingSignal

import scala.concurrent.Future

/**
 * TODO: to be deprecated after connecting 0.5 and 0.6 protocol gets deprecated
 */
trait LEGACY_connectingSignalHandler { this: AgencyAgentCommon =>

  def handleLegacySignalMsgs: PartialFunction[SignalMsgFromDriver, Future[Option[ControlMsg]]] = {
    //agency agent doesn't store the msg state as of now
    case SignalMsgFromDriver(_: LegacyConnectingSignal, _, _, _)                 => Future(None)
  }
}
