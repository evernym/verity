package com.evernym.verity.protocol.protocols.agentprovisioning.common

import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.did.DidPair
import com.evernym.verity.protocol.Control
import com.evernym.verity.protocol.engine.asyncapi.wallet.DeprecatedWalletSetupResult
import com.evernym.verity.protocol.engine.context.ProtocolContextApi

import scala.util.Try

case class AskUserAgentCreator(forDIDPair: DidPair, agentKeyDIDPair: DidPair, endpointDetailJson: String)

case class AgentCreationCompleted() extends Control with ActorMessage

trait HasAgentProvWallet {
  def ctx: ProtocolContextApi[_,_,_,_,_,_]

  protected def prepareNewAgentWalletData(forDIDPair: DidPair,
                                          walletId: String)
                                         (postNewAgentWallet: Try[DeprecatedWalletSetupResult] => Unit): Unit = {
    ctx.wallet.DEPRECATED_setupNewWallet(walletId, forDIDPair) { resp =>
      postNewAgentWallet(resp)
    }
  }

}