package com.evernym.verity.protocol.legacy.services

import com.evernym.verity.actor.agent.WalletVerKeyCacheHelper
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.cache.Cache
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.common.RemoteMsgSendingSvc
import com.evernym.verity.protocol.actor.{MsgQueueServiceProvider, WalletParam}
import com.evernym.verity.protocol.engine.{DID, RecordsEvents, SERVICES_DEPRECATION_DATE, SendsMsgs, VerKey}
import com.evernym.verity.texter.SMSSender
import com.evernym.verity.vault.{WalletAPI, WalletAPIParam, WalletConfig, AgentWalletAPI}

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
  def walletParam: WalletParam
  def generalCache: Cache
  def smsSvc: SMSSender
  def agentMsgRouter: AgentMsgRouter
  def remoteMsgSendingSvc: RemoteMsgSendingSvc
  def agentMsgTransformer: AgentMsgTransformer
  def tokenToActorMappingProvider: TokenToActorMappingProvider
  def msgQueueServiceProvider: MsgQueueServiceProvider
  def connectEndpointServiceProvider: CreateKeyEndpointServiceProvider
  def agentProvisioningServiceProvider: AgentEndpointServiceProvider
}

@deprecated("We are no longer using services. Most of these services shouldn't " +
  "be available to protocols anyway. Use ProtocolContextApi.", SERVICES_DEPRECATION_DATE)
class LegacyProtocolServicesImpl[M,E,I](val eventRecorder: RecordsEvents,
                                        val sendsMsgs: SendsMsgs,
                                        val appConfig: AppConfig,
                                        val walletParam: WalletParam,
                                        val generalCache: Cache,
                                        val smsSvc: SMSSender,
                                        val agentMsgRouter: AgentMsgRouter,
                                        val remoteMsgSendingSvc: RemoteMsgSendingSvc,
                                        val agentMsgTransformer: AgentMsgTransformer,
                                        val tokenToActorMappingProvider: TokenToActorMappingProvider,
                                        val msgQueueServiceProvider: MsgQueueServiceProvider,
                                        val connectEndpointServiceProvider: CreateKeyEndpointServiceProvider,
                                        val agentProvisioningServiceProvider: AgentEndpointServiceProvider
                                     ) extends ProtocolServices[M,E,I]


trait DEPRECATED_HasWallet {

  def appConfig: AppConfig
  def walletParam: WalletParam

  lazy val walletAPI: WalletAPI = walletParam.walletAPI
  lazy val walletConfig: WalletConfig = walletParam.walletConfig

  var walletDetail: AgentWalletAPI = _

  def initWalletDetail(seed: String): Unit = {
    walletDetail = AgentWalletAPI(walletAPI, seed)
  }

  implicit lazy val wap: WalletAPIParam = WalletAPIParam(walletDetail.walletId)

  lazy val walletVerKeyCacheHelper: WalletVerKeyCacheHelper = {
    new WalletVerKeyCacheHelper(wap, walletDetail.walletAPI, appConfig)
  }

  def getVerKeyReqViaCache(did: DID, getKeyFromPool: Boolean = false): VerKey =
    walletVerKeyCacheHelper.getVerKeyReqViaCache(did, getKeyFromPool)

  def getVerKeyViaCache(did: DID, req: Boolean = false, getKeyFromPool: Boolean = false): Option[VerKey] =
    walletVerKeyCacheHelper.getVerKeyViaCache(did, req, getKeyFromPool)

}