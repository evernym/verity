package com.evernym.verity.actor.agent

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.Exceptions.{HandledErrorException, SmsSendingFailedException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.actor.resourceusagethrottling.helper.UsageViolationActionExecutor
import com.evernym.verity.actor.{ActorContext, TokenToActorItemMapperProvider}
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.cache.base.Cache
import com.evernym.verity.cache.fetchers.{AgencyIdentityCacheFetcher, CacheValueFetcher, EndpointCacheFetcher, KeyValueMapperFetcher, LedgerGetCredDefCacheFetcher, LedgerGetSchemaCacheFetcher, LedgerVerKeyCacheFetcher}
import com.evernym.verity.config.CommonConfig.TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS
import com.evernym.verity.config.{AppConfig, AppConfigWrapper}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.http.common.{AkkaHttpMsgSendingSvc, MsgSendingSvc}
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerSvc, LedgerTxnExecutor}
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.container.actor.ActorDriverGenParam
import com.evernym.verity.protocol.engine.ProtocolRegistry
import com.evernym.verity.protocol.protocols
import com.evernym.verity.storage_services.aws_s3.{S3AlpakkaApi, StorageAPI}
import com.evernym.verity.texter.{DefaultSMSSender, SMSSender, SmsInfo, SmsSent}
import com.evernym.verity.util.Util
import com.evernym.verity.vault.service.{ActorWalletService, WalletService}
import com.evernym.verity.vault.wallet_api.{StandardWalletAPI, WalletAPI}

import scala.concurrent.Future
import scala.util.Left

trait AgentActorContext extends ActorContext {

  implicit def appConfig: AppConfig
  implicit def system: ActorSystem

  type MsgSendingSvcType = MsgSendingSvc

  lazy val generalCacheFetchers: Map[Int, CacheValueFetcher] = List (
    new KeyValueMapperFetcher(system, appConfig),
    new AgencyIdentityCacheFetcher(agentMsgRouter, appConfig),
    new EndpointCacheFetcher(ledgerSvc, appConfig),
    new LedgerVerKeyCacheFetcher(ledgerSvc, appConfig),
    new LedgerGetSchemaCacheFetcher(ledgerSvc, appConfig),
    new LedgerGetCredDefCacheFetcher(ledgerSvc, appConfig)
  ).map(f => f.id -> f).toMap

  lazy val generalCache: Cache = new Cache("GC", generalCacheFetchers)
  lazy val actionExecutor: UsageViolationActionExecutor = new UsageViolationActionExecutor(system, appConfig)
  lazy val tokenToActorItemMapperProvider: TokenToActorItemMapperProvider = new TokenToActorItemMapperProvider(system, appConfig)

  lazy val msgSendingSvc: MsgSendingSvcType = new AkkaHttpMsgSendingSvc(appConfig)

  lazy val protocolRegistry: ProtocolRegistry[ActorDriverGenParam] = protocols.protocolRegistry

  lazy val smsSvc: SMSSender = _smsSender

  lazy val agentMsgRouter: AgentMsgRouter = new AgentMsgRouter
  lazy val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(system, appConfig)
  lazy val walletProvider: LibIndyWalletProvider = new LibIndyWalletProvider(appConfig)
  lazy val walletService: WalletService = new ActorWalletService(system)
  lazy val walletAPI: WalletAPI = new StandardWalletAPI(walletService)
  lazy val agentMsgTransformer: AgentMsgTransformer = new AgentMsgTransformer(walletAPI)
  lazy val ledgerSvc: LedgerSvc = new DefaultLedgerSvc(system, appConfig, walletAPI, poolConnManager)
  lazy val s3API: StorageAPI = new S3AlpakkaApi(appConfig.config)

  def createActorSystem(): ActorSystem = {
    ActorSystem("verity", appConfig.getLoadedConfig)
  }

  object _smsSender extends SMSSender {
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


class DefaultLedgerSvc(val system: ActorSystem,
                       val appConfig: AppConfig,
                       val walletAPI: WalletAPI,
                       val ledgerPoolConnManager: LedgerPoolConnManager) extends LedgerSvc {

  override def ledgerTxnExecutor: LedgerTxnExecutor = {
    ledgerPoolConnManager.txnExecutor(Some(walletAPI))
  }
}
