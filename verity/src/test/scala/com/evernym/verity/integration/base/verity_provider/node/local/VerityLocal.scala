package com.evernym.verity.integration.base.verity_provider.node.local

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.appStateManager.GetCurrentState
import com.evernym.verity.actor.appStateManager.state.{AppState, ListeningState}
import com.evernym.verity.actor.testkit.actor.MockLedgerTxnExecutor
import com.evernym.verity.app_launcher.{DefaultAgentActorContext, HttpServer, PlatformBuilder}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.route_handlers.HttpRouteHandler
import com.evernym.verity.integration.base.verity_provider.{PortProfile, SharedEventStore}
import com.evernym.verity.ledger.{LedgerPoolConnManager, LedgerTxnExecutor}
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.testkit.mock.ledger.InMemLedgerPoolConnManager
import com.typesafe.config.{Config, ConfigFactory}

import java.nio.file.Path
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.language.postfixOps


object LocalVerity {
  lazy val waitAtMost: FiniteDuration = 25 seconds

  lazy val defaultSvcParam: ServiceParam =
    ServiceParam
      .empty
      .withLedgerTxnExecutor(new MockLedgerTxnExecutor())

  def apply(tempDir: Path,
            appSeed: String,
            portProfile: PortProfile): HttpServer = {
    LocalVerity(VerityNodeParam(tempDir, appSeed, portProfile))
  }

  def apply(verityNodeParam: VerityNodeParam,
            bootstrapApp: Boolean = true,
            trackMessageProgress: Boolean = true): HttpServer = {

    val config = buildStandardVerityConfig(verityNodeParam)

    val platform = initializeApp(new AppConfigWrapper(config), verityNodeParam.serviceParam)

    val httpServer = new HttpServer(platform, new HttpRouteHandler(platform).endpointRoutes)
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
      verityNodeParam.taaAutoAccept,
      verityNodeParam.serviceParam.flatMap(_.sharedEventStore))

    verityNodeParam.overriddenConfig match {
      case Some(config) => config.withFallback(standardConfig)
      case None         => standardConfig
    }
  }

  def buildStandardVerityConfig(verityNodeParam: VerityNodeParam): Config = {
    buildCustomVerityConfigOnly(verityNodeParam)
      .withFallback(ConfigFactory.load())
  }

  private def waitTillUp(appStateManager: ActorRef): Unit = {
    TestKit.awaitCond(isListening(appStateManager), waitAtMost, 200.millis)
  }

  private def isListening(appStateManager: ActorRef): Boolean = {
    implicit lazy val akkActorResponseTimeout: Timeout = Timeout(5.seconds)
    val fut = appStateManager ? GetCurrentState
    Await.result(fut, 3.seconds).asInstanceOf[AppState] == ListeningState
  }

  class Starter(appConfig: AppConfig, serviceParam: Option[ServiceParam]) {
    class MockDefaultAgentActorContext(override val appConfig: AppConfig, serviceParam: Option[ServiceParam])
      extends DefaultAgentActorContext {

      implicit val executor: ExecutionContextExecutor = system.dispatcher
      override lazy val poolConnManager: LedgerPoolConnManager = {
        new InMemLedgerPoolConnManager(serviceParam.flatMap(_.ledgerTxnExecutor))
      }
      override lazy val storageAPI: StorageAPI = {
        serviceParam.flatMap(_.storageAPI).getOrElse(StorageAPI.loadFromConfig(appConfig))
      }
    }

    val platform: Platform = PlatformBuilder.build(
      Option(new MockDefaultAgentActorContext(appConfig, serviceParam)))
  }

  object Starter {
    def apply(appConfig: AppConfig, serviceParam: Option[ServiceParam]): Starter =
      new Starter(appConfig, serviceParam)
  }


  private def initializeApp(appConfig: AppConfig, serviceParam: Option[ServiceParam]): Platform = {
    val s = Starter(appConfig, serviceParam)
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

class AppConfigWrapper(config: Config) extends AppConfig {
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

  def withStorageApi(storageAPI: StorageAPI): ServiceParam = {
    copy(storageAPI = Option(storageAPI))
  }

  def ledgerTxnExecutor: Option[LedgerTxnExecutor] = ledgerSvcParam.flatMap(_.ledgerTxnExecutor)
}

case class LedgerSvcParam(taaEnabled: Boolean = true,
                          taaAutoAccept: Boolean = true,
                          ledgerTxnExecutor: Option[LedgerTxnExecutor]=None)

case class VerityNodeParam(tmpDirPath: Path,
                           appSeed: String,
                           portProfile: PortProfile,
                           otherNodeArteryPorts: Seq[Int] = Seq.empty,
                           serviceParam: Option[ServiceParam] = None,
                           overriddenConfig: Option[Config] = None) {

  def taaEnabled: Boolean = serviceParam.flatMap(_.ledgerSvcParam).forall(_.taaEnabled)
  def taaAutoAccept: Boolean = serviceParam.flatMap(_.ledgerSvcParam).forall(_.taaAutoAccept)
}