package com.evernym.verity.integration

import java.nio.file.Path

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.testkit.TestKit
import com.evernym.verity.actor.Platform
import com.evernym.verity.actor.agent.AgentActorContext
import com.evernym.verity.app_launcher.PlatformLauncher
import com.evernym.verity.apphealth.AppStateConstants.STATUS_LISTENING
import com.evernym.verity.apphealth.AppStateManager
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.testkit.mock.ledger.{InMemLedgerPoolConnManager, InitLedgerData}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.language.postfixOps


object LocalVerity {
  lazy val atMost: FiniteDuration = 10 seconds

  def apply(tempDir: Path, port: PortProfile, appSeed: String,
            initData: InitLedgerData = InitLedgerData(), taaEnabled: Boolean = true,
            taaAutoAccept: Boolean = true, trackMessageProgress: Boolean = true): Platform = {
    val localConfig = LocalVerityConfig.standard(tempDir, port, taaEnabled, taaAutoAccept)

    AppConfigWrapper.DEPRECATED_setConfigWithoutValidation(localConfig)

    val platform =  initializeApp(initData)

    waitTillUp()

    bootstrapApplication(port.http, atMost, appSeed)(platform.actorSystem)

    platform
  }

  private def waitTillUp(): Unit = {
    def isListening: Boolean = {
      AppStateManager.getCurrentState.toString == STATUS_LISTENING
    }

    TestKit.awaitCond(isListening, atMost)
  }

  class Starter(initData: InitLedgerData) extends PlatformLauncher {
    class MockDefaultAgentActorContext(initData: InitLedgerData) extends DefaultAgentActorContext {
      implicit val executor: ExecutionContextExecutor = system.dispatcher
      override lazy val poolConnManager: LedgerPoolConnManager = new InMemLedgerPoolConnManager(initData)
    }

    override def agentActorContextProvider: AgentActorContext = new MockDefaultAgentActorContext(initData)
  }

  object Starter {
    def apply(initData: InitLedgerData): Starter = new Starter(initData)
  }


  private def initializeApp(initData: InitLedgerData): Platform = {
    val s = Starter(initData)

    assert(s.platform != null)

    s.start()

    s.platform
  }

  private def bootstrapApplication(port: Int, atMost: Duration, appSeed: String)(implicit ac: ActorSystem): Unit = {
    if (appSeed.length != 32) throw new Exception("Seeds must be exactly 32 characters long")

    Await.result(Http().singleRequest(
      HttpRequest(
        method=HttpMethods.POST,
        uri = s"http://localhost:$port/agency/internal/setup/key",
        entity = HttpEntity(
          ContentTypes.`application/json`,
          s"""{"seed":"$appSeed"}"""
        )
      )
    )
      , atMost)

    Await.result(Http().singleRequest(
      HttpRequest(
        method=HttpMethods.POST,
        uri = s"http://localhost:$port/agency/internal/setup/endpoint",
        entity = HttpEntity.Empty
      )
    )
      , atMost)
  }

  private def startMessageProgressTracking(port: Int, trackingId: String = "127.0.0.1")(implicit ac: ActorSystem): Unit = {
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
