package com.evernym.verity.actor.agent

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.util2.Exceptions.{HandledErrorException, SmsSendingFailedException}
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.actor.ActorContext
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.cache.base.{Cache, FetcherParam}
import com.evernym.verity.cache.fetchers.{AgencyIdentityCacheFetcher, CacheValueFetcher, EndpointCacheFetcher, KeyValueMapperFetcher, LedgerGetCredDefCacheFetcher, LedgerGetSchemaCacheFetcher, LedgerVerKeyCacheFetcher}
import com.evernym.verity.config.CommonConfig.TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerSvc, LedgerTxnExecutor}
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.protocol.container.actor.ActorDriverGenParam
import com.evernym.verity.protocol.engine.ProtocolRegistry
import com.evernym.verity.protocol.protocols
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.texter.{DefaultSMSSender, SMSSender, SmsInfo, SmsSent}
import com.evernym.verity.transports.http.AkkaHttpMsgSendingSvc
import com.evernym.verity.transports.MsgSendingSvc
import com.evernym.verity.util.Util
import com.evernym.verity.vault.service.ActorWalletService
import com.evernym.verity.vault.wallet_api.{StandardWalletAPI, WalletAPI}

import scala.concurrent.Future
import scala.util.Left

trait AgentActorContext extends ActorContext {

  implicit def appConfig: AppConfig
  implicit def system: ActorSystem

  lazy val generalCacheFetchers: Map[FetcherParam, CacheValueFetcher] = List (
    new KeyValueMapperFetcher(system, appConfig),
    new AgencyIdentityCacheFetcher(agentMsgRouter, appConfig),
    new EndpointCacheFetcher(ledgerSvc, appConfig),
    new LedgerVerKeyCacheFetcher(ledgerSvc, appConfig),
    new LedgerGetSchemaCacheFetcher(ledgerSvc, appConfig),
    new LedgerGetCredDefCacheFetcher(ledgerSvc, appConfig)
  ).map(f => f.fetcherParam -> f).toMap

  lazy val generalCache: Cache = new Cache("GC", generalCacheFetchers)
  lazy val msgSendingSvc: MsgSendingSvc = new AkkaHttpMsgSendingSvc(appConfig.config)
  lazy val protocolRegistry: ProtocolRegistry[ActorDriverGenParam] = protocols.protocolRegistry
  lazy val smsSvc: SMSSender = createSmsSender()
  lazy val agentMsgRouter: AgentMsgRouter = new AgentMsgRouter
  lazy val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(system, appConfig)
  lazy val walletAPI: WalletAPI = new StandardWalletAPI(new ActorWalletService(system))
  lazy val agentMsgTransformer: AgentMsgTransformer = new AgentMsgTransformer(walletAPI)
  lazy val ledgerSvc: LedgerSvc = new DefaultLedgerSvc(system, appConfig, walletAPI, poolConnManager)
  lazy val storageAPI: StorageAPI = StorageAPI.loadFromConfig(appConfig)

  def createActorSystem(): ActorSystem = {
    ActorSystem("verity", appConfig.getLoadedConfig)
  }

  private def createSmsSender(): SMSSender = {
    new SMSSender {
      implicit lazy val timeout: Timeout = Util.buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)
      val smsSender: ActorRef = system.actorOf(DefaultSMSSender.props(appConfig), "sms-sender")

      override def sendMessage(smsInfo: SmsInfo): Future[Either[HandledErrorException, String]] = {
        val smsSendFut = smsSender ? smsInfo
        smsSendFut.map {
          case _: SmsSent => Right("sms sent successfully")
          case er: HandledErrorException => Left(er)
          case x => Left(new SmsSendingFailedException(Option(x.toString)))
        }.recover {
          case e: Exception => Left(new SmsSendingFailedException(Option(e.toString)))
        }
      }
    }
  }
}


class DefaultLedgerSvc(val system: ActorSystem,
                       val appConfig: AppConfig,
                       val walletAPI: WalletAPI,
                       val ledgerPoolConnManager: LedgerPoolConnManager) extends LedgerSvc {

  override def ledgerTxnExecutor: LedgerTxnExecutor = {
    ledgerPoolConnManager.txnExecutor(Some(walletAPI))
  }
}
