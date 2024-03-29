package com.evernym.verity.actor.agent

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.util2.Exceptions.{HandledErrorException, SmsSendingFailedException}
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.actor.agent.msgrouter.AgentMsgRouter
import com.evernym.verity.actor.ActorContext
import com.evernym.verity.agentmsg.msgpacker.AgentMsgTransformer
import com.evernym.verity.cache.base.{Cache, FetcherParam}
import com.evernym.verity.cache.fetchers.{AgencyIdentityCacheFetcher, CacheValueFetcher, EndpointCacheFetcher, KeyValueMapperFetcher, LedgerVerKeyCacheFetcher}
import com.evernym.verity.cache.providers.{CacheProvider, CaffeineCacheParam, CaffeineCacheProvider}
import com.evernym.verity.config.ConfigConstants.{EVENT_SINK, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.eventing.adapters.kafka.producer.{KafkaProducerAdapter, ProducerSettingsProvider}
import com.evernym.verity.eventing.ports.producer.ProducerPort
import com.evernym.verity.ledger.{LedgerPoolConnManager, LegacyLedgerSvc, LedgerTxnExecutor}
import com.evernym.verity.vdrtools.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.msgoutbox.outbox.msg_dispatcher.webhook.oauth.access_token_refresher.{AccessTokenRefreshers, OAuthAccessTokenRefresher}
import com.evernym.verity.observability.metrics.{MetricsWriter, MetricsWriterExtension}
import com.evernym.verity.protocol.container.actor.ActorDriverGenParam
import com.evernym.verity.protocol.engine.registry.ProtocolRegistry
import com.evernym.verity.protocol.protocols
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.texter.{DefaultSMSSender, SMSSender, SmsInfo, SmsReqSent}
import com.evernym.verity.transports.http.AkkaHttpMsgSendingSvc
import com.evernym.verity.transports.MsgSendingSvc
import com.evernym.verity.util.Util
import com.evernym.verity.vault.service.ActorWalletService
import com.evernym.verity.vault.wallet_api.{StandardWalletAPI, WalletAPI}
import com.evernym.verity.vdr.{VDRActorAdapter, VDRAdapter}
import com.evernym.verity.vdr.service.{VDRToolsConfig, VDRToolsFactory, VdrToolsBuilderImpl}

import scala.concurrent.{ExecutionContext, Future}


trait AgentActorContext
  extends ActorContext
  with HasExecutionContextProvider {

  private implicit val executionContext: ExecutionContext = futureExecutionContext
  implicit def appConfig: AppConfig
  implicit def system: ActorSystem

  lazy val generalCacheFetchers: Map[FetcherParam, CacheValueFetcher] = List (
    new KeyValueMapperFetcher(system, appConfig, futureExecutionContext),
    new AgencyIdentityCacheFetcher(agentMsgRouter, appConfig, futureExecutionContext),
    new EndpointCacheFetcher(legacyLedgerSvc, appConfig, futureExecutionContext),
    new LedgerVerKeyCacheFetcher(vdrAdapter, appConfig, futureExecutionContext)
  ).map(f => f.fetcherParam -> f).toMap

  lazy val vdrCache: CacheProvider = new CaffeineCacheProvider(CaffeineCacheParam(None, None, None, None))

  lazy val metricsWriter: MetricsWriter = MetricsWriterExtension(system).get()
  lazy val generalCache: Cache = new Cache("GC", generalCacheFetchers, metricsWriter, futureExecutionContext)
  lazy val msgSendingSvc: MsgSendingSvc = new AkkaHttpMsgSendingSvc(appConfig.config, metricsWriter, futureExecutionContext)
  lazy val protocolRegistry: ProtocolRegistry[ActorDriverGenParam] = protocols.protocolRegistry
  lazy val smsSvc: SMSSender = createSmsSender()
  lazy val agentMsgRouter: AgentMsgRouter = new AgentMsgRouter(futureExecutionContext)
  lazy val poolConnManager: LedgerPoolConnManager = new IndyLedgerPoolConnManager(system, appConfig, futureExecutionContext)
  lazy val actorWalletService: ActorWalletService = new ActorWalletService(system, appConfig, poolConnManager, futureExecutionContext)
  lazy val walletAPI: WalletAPI = new StandardWalletAPI(actorWalletService)
  lazy val agentMsgTransformer: AgentMsgTransformer = new AgentMsgTransformer(walletAPI, appConfig, futureExecutionContext)
  lazy val legacyLedgerSvc: LegacyLedgerSvc = new DefaultLegacyLedgerSvc(system, appConfig, walletAPI, poolConnManager)
  lazy val storageAPI: StorageAPI = StorageAPI.loadFromConfig(appConfig, futureExecutionContext)
  lazy val vdrBuilderFactory: VDRToolsFactory = () => new VdrToolsBuilderImpl(appConfig)(executionContext)
  lazy val vdrAdapter: VDRAdapter = createVDRAdapter(vdrBuilderFactory, appConfig)

  lazy val eventProducerAdapter: ProducerPort = {
    val configPath = appConfig.getStringReq(EVENT_SINK)
    val clazz = appConfig.getStringReq(s"$configPath.builder-class")
    Class
      .forName(clazz)
      .getConstructor()
      .newInstance()
      .asInstanceOf[EventProducerAdapterBuilder]
      .build(appConfig, futureExecutionContext, system)
  }

  //NOTE: this 'oAuthAccessTokenRefreshers' is only need here until we switch to the outbox solution
  val oAuthAccessTokenRefreshers: AccessTokenRefreshers = new AccessTokenRefreshers {
    override def refreshers: Map[Version, Behavior[OAuthAccessTokenRefresher.Cmd]] =
      OAuthAccessTokenRefresher
        .SUPPORTED_VERSIONS
        .map (v => v -> OAuthAccessTokenRefresher.getRefresher(v, executionContext))
        .toMap
  }

  def createActorSystem(): ActorSystem = {
    ActorSystem("verity", appConfig.getLoadedConfig)
  }

  private def createSmsSender(): SMSSender = {
    new SMSSender {
      implicit lazy val timeout: Timeout = Util.buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)
      val smsSender: ActorRef = system.actorOf(DefaultSMSSender.props(appConfig, futureExecutionContext), "sms-sender")

      override def sendMessage(smsInfo: SmsInfo): Future[Either[HandledErrorException, String]] = {
        val smsSendFut = smsSender ? smsInfo
        smsSendFut.map {
          case _: SmsReqSent => Right("sms request sent successfully")
          case er: HandledErrorException => Left(er)
          case x => Left(new SmsSendingFailedException(Option(x.toString)))
        }.recover {
          case e: Exception => Left(new SmsSendingFailedException(Option(e.toString)))
        }
      }
    }
  }

  private def createVDRAdapter(vdrToolsFactory: VDRToolsFactory,
                               appConfig: AppConfig)
                              (implicit ec: ExecutionContext, as: ActorSystem): VDRActorAdapter = {
    val timeout: Timeout = Util.buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)
    new VDRActorAdapter(
      vdrToolsFactory,
      VDRToolsConfig.load(appConfig.config),
      Option(timeout)
    )(ec, as.toTyped)
  }

}


class DefaultLegacyLedgerSvc(val system: ActorSystem,
                             val appConfig: AppConfig,
                             val walletAPI: WalletAPI,
                             val ledgerPoolConnManager: LedgerPoolConnManager)
                            (implicit val executionContext: ExecutionContext)
  extends LegacyLedgerSvc {

  override def ledgerTxnExecutor: LedgerTxnExecutor = {
    ledgerPoolConnManager.txnExecutor(Some(walletAPI))
  }
}

trait EventProducerAdapterBuilder {
  def build(appConfig: AppConfig, executionContext: ExecutionContext, actorSystem: ActorSystem): ProducerPort
}

class KafkaEventProducerAdapterBuilder
  extends EventProducerAdapterBuilder {

  override def build(appConfig: AppConfig, executionContext: ExecutionContext, actorSystem: ActorSystem): ProducerPort = {
    KafkaProducerAdapter(
      ProducerSettingsProvider(appConfig.config)
    )(executionContext, actorSystem.toTyped)
  }
}