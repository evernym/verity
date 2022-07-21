package com.evernym.verity.integration.base.verity_provider.node.local

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import com.evernym.verity.actor.Platform
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.appStateManager.GetCurrentState
import com.evernym.verity.actor.appStateManager.state.{AppState, ListeningState}
import com.evernym.verity.app_launcher.{DefaultAgentActorContext, HttpServer, PlatformBuilder}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.route_handlers.HttpRouteHandler
import com.evernym.verity.integration.base.verity_provider.{PortProfile, SharedEventStore}
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerTxnExecutor}
import com.evernym.verity.protocol.engine.MockVDRAdapter
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.mock.ledger.InMemLedgerPoolConnManager
import com.evernym.verity.vdr.base.INDY_SOVRIN_NAMESPACE
import com.typesafe.config.{Config, ConfigFactory, ConfigMergeable}
import com.evernym.verity.vdr.{MockIndyLedger, MockLedgerRegistryBuilder, MockVdrToolsBuilder, VDRAdapter}
import com.evernym.verity.vdr.service.{VDRToolsFactory, VdrTools}

import java.nio.file.Path
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.language.postfixOps


object LocalVerity {
  lazy val waitAtMost: FiniteDuration = 25 seconds

  def apply(tempDir: Path,
            appSeed: String,
            portProfile: PortProfile,
            ecp: ExecutionContextProvider): HttpServer = {
    LocalVerity(VerityNodeParam(tempDir, appSeed, portProfile), ecp)
  }

  def apply(verityNodeParam: VerityNodeParam,
            ecp: ExecutionContextProvider,
            baseConfig: Option[ConfigMergeable] = None): HttpServer = {
    val config = buildStandardVerityConfig(verityNodeParam, baseConfig.getOrElse(ConfigFactory.load()))
    val appConfig = new AppConfigWrapper(config)
    val platform = initializeApp(appConfig, verityNodeParam.serviceParam, ecp)
    val httpServer = new HttpServer(
      platform,
      new HttpRouteHandler(platform, ecp.futureExecutionContext).endpointRoutes,
      ecp.futureExecutionContext
    )
    httpServer.start()
    waitTillUp(platform.appStateManager)
    httpServer
  }

  def buildCustomVerityConfigOnly(verityNodeParam: VerityNodeParam): Config = {
    val standardConfig = VerityLocalConfig.customOnly(
      verityNodeParam.tmpDirPath,
      verityNodeParam.portProfile,
      verityNodeParam.otherNodeArteryPorts,
      verityNodeParam.taaEnabled,
      verityNodeParam.serviceParam.flatMap(_.sharedEventStore))

    verityNodeParam.overriddenConfig match {
      case Some(config) => config.withFallback(standardConfig)
      case None         => standardConfig
    }
  }

  def buildStandardVerityConfig(verityNodeParam: VerityNodeParam, baseConfig: ConfigMergeable): Config = {
    buildCustomVerityConfigOnly(verityNodeParam)
      .withFallback(baseConfig)
  }

  private def waitTillUp(appStateManager: ActorRef): Unit = {
    TestKit.awaitCond(isListening(appStateManager), waitAtMost, 200.millis)
  }

  private def isListening(appStateManager: ActorRef): Boolean = {
    implicit lazy val akkActorResponseTimeout: Timeout = Timeout(5.seconds)
    val fut = appStateManager ? GetCurrentState
    Await.result(fut, 3.seconds).asInstanceOf[AppState] == ListeningState
  }

  class Starter(appConfig: AppConfig, serviceParam: Option[ServiceParam],
                executionContextProvider: ExecutionContextProvider) {

    class MockDefaultAgentActorContext(override val appConfig: AppConfig,
                                       serviceParam: Option[ServiceParam],
                                       executionContextProvider: ExecutionContextProvider)
      extends DefaultAgentActorContext(executionContextProvider, appConfig) {

      implicit val executor: ExecutionContextExecutor = system.dispatcher
      override lazy val poolConnManager: LedgerPoolConnManager = {
        new InMemLedgerPoolConnManager(
          executionContextProvider.futureExecutionContext,
          serviceParam.flatMap(_.ledgerTxnExecutor)
        )(executor)
      }
      override lazy val storageAPI: StorageAPI = {
        serviceParam
          .flatMap(_.storageAPI)
          .getOrElse(StorageAPI.loadFromConfig(appConfig, executionContextProvider.futureExecutionContext))
      }

      val testVdrLedgerRegistry = MockLedgerRegistryBuilder(Map(INDY_SOVRIN_NAMESPACE -> MockIndyLedger("genesis.txn file path", None))).build()
      override lazy val vdrBuilderFactory: VDRToolsFactory = () => new MockVdrToolsBuilder(testVdrLedgerRegistry, serviceParam.flatMap(_.vdrTools))
      override lazy val vdrAdapter: VDRAdapter = new MockVDRAdapter(vdrBuilderFactory)(futureExecutionContext)
    }

    val platform: Platform = PlatformBuilder.build(
      executionContextProvider,
      appConfig,
      Option(new MockDefaultAgentActorContext(appConfig, serviceParam, executionContextProvider))
    )
    platform.eventConsumerAdapter.map(_.start())
  }

  object Starter {
    def apply(appConfig: AppConfig,
              serviceParam: Option[ServiceParam],
              executionContextProvider: ExecutionContextProvider): Starter =
      new Starter(appConfig, serviceParam, executionContextProvider)
  }


  private def initializeApp(appConfig: AppConfig,
                            serviceParam: Option[ServiceParam],
                            executionContextProvider: ExecutionContextProvider): Platform = {
    val s = Starter(appConfig, serviceParam, executionContextProvider)
    assert(s.platform != null)
    s.platform
  }

  private def startMessageProgressTracking(port: Int, trackingId: String = "global")(implicit ac: ActorSystem): Unit = {
    if (trackingId.isEmpty) throw new Exception("trackingId must not be blank")

    Await.result(Http().singleRequest(
      HttpRequest(
        method=HttpMethods.POST,
        uri = s"http://localhost:$port/agency/internal/msg-progress-tracker/$trackingId"
      )
    )
      , waitAtMost)
  }
}

class AppConfigWrapper(var config: Config) extends AppConfig {
  DEPRECATED_setConfigWithoutValidation(config)
}

object ServiceParam {
  def empty: ServiceParam = ServiceParam()
}

/**
 * When we wants to create LocalVerity instance, it will need to use some external services
 * like (ledger etc). Those services or related parameters will be provided by this case class.
 * This way, 'verity restart' will retain any changes done in those external services (specially storage)
 * which will help it run correctly if test wants to restart verity instances in between of the test.
 *
 * @param ledgerSvcParam
 * @param sharedEventStore
 */
case class ServiceParam(ledgerSvcParam: Option[LedgerSvcParam]=None,
                        storageAPI: Option[StorageAPI]=None,
                        sharedEventStore: Option[SharedEventStore]=None) {

  def withLedgerTxnExecutor(txnExecutor: LedgerTxnExecutor): ServiceParam = {
    val lsp = ledgerSvcParam.getOrElse(LedgerSvcParam())
    copy(ledgerSvcParam = Option(lsp.copy(ledgerTxnExecutor = Option(txnExecutor))))
  }

  def withVdrTools(vdrTools: VdrTools): ServiceParam = {
    val lsp = ledgerSvcParam.getOrElse(LedgerSvcParam())
    copy(ledgerSvcParam = Option(lsp.copy(vdrTools = Option(vdrTools))))
  }

  def withStorageApi(storageAPI: StorageAPI): ServiceParam = {
    copy(storageAPI = Option(storageAPI))
  }

  def ledgerTxnExecutor: Option[LedgerTxnExecutor] = ledgerSvcParam.flatMap(_.ledgerTxnExecutor)
  def vdrTools: Option[VdrTools] = ledgerSvcParam.flatMap(_.vdrTools)
}

case class LedgerSvcParam(taaEnabled: Boolean = true,
                          taaAutoAccept: Boolean = true,
                          ledgerTxnExecutor: Option[LedgerTxnExecutor]=None,
                          vdrTools: Option[VdrTools]=None)

case class VerityNodeParam(tmpDirPath: Path,
                           appSeed: String,
                           portProfile: PortProfile,
                           otherNodeArteryPorts: Seq[Int] = Seq.empty,
                           serviceParam: Option[ServiceParam] = None,
                           overriddenConfig: Option[Config] = None) {

  def taaEnabled: Boolean = serviceParam.flatMap(_.ledgerSvcParam).forall(_.taaEnabled)
}