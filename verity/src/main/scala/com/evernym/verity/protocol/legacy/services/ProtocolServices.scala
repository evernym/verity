package com.evernym.verity.protocol.legacy.services

import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.appStateManager.AppStateEvent
import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet, GetVerKey, GetVerKeyOpt, GetVerKeyResp, NewKeyCreated, StoreTheirKey, TheirKeyStored, WalletCreated}
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.cache.base.Cache
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.MsgSendingSvc
import com.evernym.verity.protocol.container.actor.MsgQueueServiceProvider
import com.evernym.verity.protocol.engine.{DID, ProtocolContextApi, SERVICES_DEPRECATION_DATE}
import com.evernym.verity.vault.wallet_api.WalletAPI
import com.evernym.verity.vault.WalletAPIParam

/** General services provided to protocols.
  *
  * Protocols should not have to worry about how to persist, or how to
  * authenticate messages, or versioning of messages. Provided services
  * can help keep the protocol definition clean and focused on the business
  * of the protocol.
  *
  * eventRecorder is a service that records state transitioning events as they occur
 *
  * @tparam E event type
  * @tparam I message recipient identifier type
  */
trait ProtocolServices[M,E,I] {
  def appConfig: AppConfig
  def walletAPI: WalletAPI
  def generalCache: Cache
  def msgSendingSvc: MsgSendingSvc
  def agentMsgTransformer: AgentMsgTransformer
  def tokenToActorMappingProvider: TokenToActorMappingProvider
  def msgQueueServiceProvider: MsgQueueServiceProvider
  def connectEndpointServiceProvider: CreateKeyEndpointServiceProvider
  def publishAppStateEvent: AppStateEvent => Unit
}

@deprecated("We are no longer using services. Most of these services shouldn't " +
  "be available to protocols anyway. Use ProtocolContextApi.", SERVICES_DEPRECATION_DATE)
class LegacyProtocolServicesImpl[M,E,I](val appConfig: AppConfig,
                                        val walletAPI: WalletAPI, // used by connecting (0.5 and 0.6) and agent provisioning (0.5 and 0.6)
                                        val generalCache: Cache, //only used in legacy connecting (0.5 and 0.6) protocols
                                        val msgSendingSvc: MsgSendingSvc, //only used in legacy connecting (0.5 and 0.6) protocols
                                        val agentMsgTransformer: AgentMsgTransformer, //only used in legacy connecting (0.5 and 0.6) protocols
                                        val publishAppStateEvent: AppStateEvent => Unit,
                                        val tokenToActorMappingProvider: TokenToActorMappingProvider, //only used in legacy connecting (0.5 and 0.6) protocols
                                        val msgQueueServiceProvider: MsgQueueServiceProvider, //only used in legacy connecting (0.5 and 0.6) protocols
                                        val connectEndpointServiceProvider: CreateKeyEndpointServiceProvider //only used in legacy connecting (0.5 and 0.6) protocols
) extends ProtocolServices[M,E,I]


trait DEPRECATED_HasWallet {
  def ctx: ProtocolContextApi[_,_,_,_,_,_]

  def appConfig: AppConfig
  def walletAPI: WalletAPI

  var walletId: String = _
  implicit lazy val wap: WalletAPIParam = WalletAPIParam(walletId)

  def initWalletDetail(seed: String): Unit = walletId = seed

  protected def prepareNewAgentWalletData(forDIDPair: DidPair, walletId: String): NewKeyCreated = {
    val agentWAP = WalletAPIParam(walletId)
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[WalletCreated.type](CreateWallet)(agentWAP))
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[TheirKeyStored](StoreTheirKey(forDIDPair.DID, forDIDPair.verKey))(agentWAP))
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[NewKeyCreated](CreateNewKey())(agentWAP))
  }

  def getVerKeyReqViaCache(did: DID, getKeyFromPool: Boolean = false): GetVerKeyResp =
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[GetVerKeyResp](GetVerKey(did, getKeyFromPool)))

  def getVerKeyViaCache(did: DID, getKeyFromPool: Boolean = false): Option[GetVerKeyResp] =
    ctx.DEPRECATED_convertAsyncToSync(walletAPI.executeAsync[Option[GetVerKeyResp]](GetVerKeyOpt(did, getKeyFromPool)))

}
