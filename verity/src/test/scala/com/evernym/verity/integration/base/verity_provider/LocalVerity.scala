package com.evernym.verity.integration.base.verity_provider

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import akka.pattern.ask
import akka.testkit.TestKit
import akka.util.Timeout
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.{AgencyPublicDid, Platform}
import com.evernym.verity.actor.appStateManager.GetCurrentState
import com.evernym.verity.actor.appStateManager.state.{AppState, ListeningState}
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec
import com.evernym.verity.app_launcher.{DefaultAgentActorContext, HttpServer, PlatformBuilder}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.http.route_handlers.HttpRouteHandler
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.testkit.mock.ledger.{InMemLedgerPoolConnManager, InitLedgerData}
import com.typesafe.config.Config

import java.nio.file.Path
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.language.postfixOps


object LocalVerity {
  lazy val atMost: FiniteDuration = 25 seconds

  def apply(tempDir: Path,
            port: PortProfile,
            appSeed: String,
            initData: InitLedgerData = InitLedgerData(),
            taaEnabled: Boolean = true,
            taaAutoAccept: Boolean = true,
            trackMessageProgress: Boolean = true,
            overriddenConfig: Option[Config] = None): HttpServer = {

    val localConfig = LocalVerityConfig.standard(tempDir, port, taaEnabled, taaAutoAccept)
    val finalConfig = overriddenConfig match {
      case Some(config) => config.withFallback(localConfig)
      case None         => localConfig
    }
    val appConfig = new AppConfigWrapper(finalConfig)
    val platform = initializeApp(initData, appConfig)

    val httpServer = new HttpServer(platform, new HttpRouteHandler(platform).endpointRoutes)
    httpServer.start()

    waitTillUp(platform.appStateManager)
    bootstrapApplication(port.http, atMost, appSeed)(platform.actorSystem)
    httpServer
  }

  private def waitTillUp(appStateManager: ActorRef): Unit = {
    TestKit.awaitCond(isListening(appStateManager), atMost, 2.seconds)
  }

  def isListening(appStateManager: ActorRef): Boolean = {
    implicit lazy val akkActorResponseTimeout: Timeout = Timeout(5.seconds)
    val fut = appStateManager ? GetCurrentState
    Await.result(fut, 3.seconds).asInstanceOf[AppState] == ListeningState
  }

  class Starter(initData: InitLedgerData, appConfig: AppConfig) {
    class MockDefaultAgentActorContext(initData: InitLedgerData, override val appConfig: AppConfig)
      extends DefaultAgentActorContext {
        implicit val executor: ExecutionContextExecutor = system.dispatcher
        override lazy val poolConnManager: LedgerPoolConnManager = new InMemLedgerPoolConnManager(initData)
    }

    val platform: Platform = PlatformBuilder.build(Option(new MockDefaultAgentActorContext(initData, appConfig)))
  }

  object Starter {
    def apply(initData: InitLedgerData, appConfig: AppConfig): Starter = new Starter(initData, appConfig)
  }


  private def initializeApp(initData: InitLedgerData, appConfig: AppConfig): Platform = {
    val s = Starter(initData, appConfig)

    assert(s.platform != null)

    s.platform
  }

  private def bootstrapApplication(port: Int, atMost: Duration, appSeed: String)(implicit ac: ActorSystem): Unit = {
    if (appSeed.length != 32) throw new Exception("Seeds must be exactly 32 characters long")

    val keySetupResp = Await.result(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.POST,
          uri = s"http://localhost:$port/agency/internal/setup/key",
          entity = HttpEntity(
            ContentTypes.`application/json`,
            s"""{"seed":"$appSeed"}"""
          )
        )
      )
      , atMost
    )

    checkAgencyKeySetup(keySetupResp)

    val endpointSetupResp = Await.result(
      Http().singleRequest(
        HttpRequest(
          method=HttpMethods.POST,
          uri = s"http://localhost:$port/agency/internal/setup/endpoint",
          entity = HttpEntity.Empty
        )
      )
      , atMost
    )

    checkAgencyEndpointSetup(endpointSetupResp)
  }

  private def checkAgencyKeySetup(httpResp: HttpResponse)(implicit ac: ActorSystem): Unit = {
    val json = parseHttpResponse(httpResp)
    val apd = JacksonMsgCodec.fromJson[AgencyPublicDid](json)
    require(apd.DID.nonEmpty, "agency DID should not be empty")
    require(apd.verKey.nonEmpty, "agency verKey should not be empty")
  }

  private def checkAgencyEndpointSetup(httpResp: HttpResponse)(implicit ac: ActorSystem): Unit = {
    val json = parseHttpResponse(httpResp)
    require(json == "OK", "agency endpoint not updated")
  }

  protected def parseHttpResponse(resp: HttpResponse)(implicit ac: ActorSystem): String = {
    awaitFut(resp.entity.dataBytes.runReduce(_ ++ _).map(_.utf8String))
  }

  protected def awaitFut[T](fut: Future[T]): T = {
    Await.result(fut, Duration(20, SECONDS))
  }

  private def startMessageProgressTracking(port: Int, trackingId: String = "global")(implicit ac: ActorSystem): Unit = {
    if (trackingId.isEmpty) throw new Exception("trackingId must not be blank")

    Await.result(Http().singleRequest(
      HttpRequest(
        method=HttpMethods.POST,
        uri = s"http://localhost:$port/agency/internal/msg-progress-tracker/$trackingId"
      )
    )
      , atMost)
  }
}

class AppConfigWrapper(config: Config) extends AppConfig {
  DEPRECATED_setConfigWithoutValidation(config)
}