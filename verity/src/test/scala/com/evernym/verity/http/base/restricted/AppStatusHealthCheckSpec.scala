package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.model.StatusCodes._
import akka.util.ByteString
import com.evernym.verity.actor.appStateManager.{AppStateDetailedResp, CauseDetail, ListeningSuccessful, SuccessEvent}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.http.route_handlers.restricted.UpdateAppStatus
import com.evernym.verity.{AppVersion, BuildInfo}
import org.scalatest.time.{Seconds, Span}


trait AppStatusHealthCheckSpec { this : EdgeEndpointBaseSpec =>

  def testSetAppStateAsListening(): Unit = {
    "when sent update app state api call" - {
      "should respond with ok" taggedAs UNSAFE_IgnoreLog in {
        switchAppToListeningSate()
      }
    }
  }

  def switchAppToListeningSate(): Unit = {
    val currentState = buildGetReq("/agency/internal/health-check/application-state?detail=Y") ~> epRoutes ~> check {
      status shouldBe OK
      responseTo[AppStateDetailedResp].currentState
    }
    if (currentState == STATUS_INITIALIZING) {
      publishAppStateEvent(SuccessEvent(ListeningSuccessful, CONTEXT_AGENT_SERVICE_INIT,
        causeDetail = CauseDetail("agent-service-started", "agent-service-started-listening-successfully"),
        msg = Option("test setting app state to listening")))
    } else {
      val msg = DefaultMsgCodec.toJson(UpdateAppStatus())
      buildPutReq("/agency/internal/health-check/application-state",
        HttpEntity.Strict(ContentTypes.`application/json`, ByteString.fromString(msg))) ~> epRoutes ~> check {
        status shouldBe OK
      }
    }
  }

  def testCheckAppStateIsListening(): Unit = {
    "when sent get app state api call" - {
      "should respond with listening app state" taggedAs UNSAFE_IgnoreLog in {
        eventually(timeout(Span(5, Seconds))) {
          buildGetReq("/agency/internal/health-check/application-state?detail=Y") ~> epRoutes ~> check {
            status shouldBe OK
            val aps = responseTo[AppStateDetailedResp]
            aps.currentState shouldBe STATUS_LISTENING
          }
        }
      }
    }
  }

  def testAppStateHealthCheck(): Unit = {

    "when sent get app state api call" - {
      "should respond with listening app state" in {

        switchAppToListeningSate()

        eventually(timeout(Span(5, Seconds)), interval(Span(2, Seconds))) {
          buildGetReq("/agency/internal/health-check/application-state?detail=Y") ~> epRoutes ~> check {
            status shouldBe OK
            val appState = responseTo[AppStateDetailedResp]
            appState.currentState shouldBe STATUS_LISTENING
          }
        }
      }
    }

    "when sent update app state api call" - {
      "should respond with ok" taggedAs UNSAFE_IgnoreLog in {
        buildPutReq("/agency/internal/health-check/application-state") ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

    "when sent get app state api call after updating it" - {
      "should respond with listening app state" in {
        buildGetReq("/agency/internal/health-check/application-state?detail=Y") ~> epRoutes ~> check {
          val appVersion: AppVersion = BuildInfo.version
          val staticAppVersion = s"${appVersion.major}.${appVersion.minor}.${appVersion.patch}"
          status shouldBe OK
          val detailedResp: AppStateDetailedResp = responseTo[AppStateDetailedResp]
          val pieces: Array[String] = detailedResp.runningVersion.split('.')
          pieces.length should be >= 3
          pieces.length should be <= 4
          pieces(0).toInt should be >= 0
          pieces(1).toInt should be >= 0
          assert(pieces(2) == "0-SNAPSHOT" || pieces.length == 4)
          detailedResp.runningVersion shouldBe staticAppVersion
          detailedResp.currentState shouldBe STATUS_LISTENING
        }
      }
    }
  }
}
