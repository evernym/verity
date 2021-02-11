package com.evernym.verity.protocol.legacy.services

import com.evernym.verity.actor.agent.WalletVerKeyCacheHelper
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.cache.Cache
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.MsgSendingSvc
import com.evernym.verity.protocol.actor.MsgQueueServiceProvider
import com.evernym.verity.protocol.engine.{DID, RecordsEvents, SERVICES_DEPRECATION_DATE, SendsMsgs, VerKey}
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
}

@deprecated("We are no longer using services. Most of these services shouldn't " +
  "be available to protocols anyway. Use ProtocolContextApi.", SERVICES_DEPRECATION_DATE)
class LegacyProtocolServicesImpl[M,E,I](val eventRecorder: RecordsEvents,
                                        val sendsMsgs: SendsMsgs,
                                        val appConfig: AppConfig,
                                        val walletAPI: WalletAPI,
                                        val generalCache: Cache,
                                        val msgSendingSvc: MsgSendingSvc,
                                        val agentMsgTransformer: AgentMsgTransformer,
                                        val tokenToActorMappingProvider: TokenToActorMappingProvider,
                                        val msgQueueServiceProvider: MsgQueueServiceProvider,
                                        val connectEndpointServiceProvider: CreateKeyEndpointServiceProvider
                                     ) extends ProtocolServices[M,E,I]


trait DEPRECATED_HasWallet {

  def appConfig: AppConfig
  def walletAPI: WalletAPI

  var walletId: String = _
  implicit lazy val wap: WalletAPIParam = WalletAPIParam(walletId)

  def initWalletDetail(seed: String): Unit = walletId = seed

  lazy val walletVerKeyCacheHelper: WalletVerKeyCacheHelper = {
    new WalletVerKeyCacheHelper(WalletAPIParam(walletId), walletAPI, appConfig)
  }

  def getVerKeyReqViaCache(did: DID, getKeyFromPool: Boolean = false): VerKey =
    walletVerKeyCacheHelper.getVerKeyReqViaCache(did, getKeyFromPool)

  def getVerKeyViaCache(did: DID, req: Boolean = false, getKeyFromPool: Boolean = false): Option[VerKey] =
    walletVerKeyCacheHelper.getVerKeyViaCache(did, req, getKeyFromPool)

}