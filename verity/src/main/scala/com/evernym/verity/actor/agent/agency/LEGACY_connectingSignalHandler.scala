package com.evernym.verity.actor.agent.agency

import com.evernym.verity.actor.agent.msghandler.incoming.{ControlMsg, SignalMsgParam}
import com.evernym.verity.protocol.protocols.connecting.common.LegacyConnectingSignal

import scala.concurrent.{ExecutionContext, Future}

/**
 * TODO: to be deprecated after connecting 0.5 and 0.6 protocol gets deprecated
 */
trait LEGACY_connectingSignalHandler { this: AgencyAgentCommon =>

  private implicit val executionContext: ExecutionContext = futureExecutionContext

  def handleLegacySignalMsgs: PartialFunction[SignalMsgParam, Future[Option[ControlMsg]]] = {
    //agency agent doesn't store the msg state as of now
    case SignalMsgParam(_: LegacyConnectingSignal, _)                 => Future(None)
  }
}
