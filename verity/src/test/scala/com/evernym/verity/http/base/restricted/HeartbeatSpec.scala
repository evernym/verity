package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.actor.appStateManager.{DrainingStarted, ErrorEvent, MildSystemError, StartDraining}
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.appStateManager.AppStateConstants._
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import org.scalatest.time.{Millis, Seconds, Span}


trait HeartbeatSpec  { this : EdgeEndpointBaseSpec =>

  def testHeartbeat(): Unit = {

    "when sent get heartbeat api call" - {
      "should respond with status code GNR-129 indicating the Agency is accepting traffic" taggedAs UNSAFE_IgnoreLog in {

        switchAppToListeningSate()

        eventually(timeout(Span(5, Seconds)), interval(Span(200, Millis))) {
          buildGetReq("/agency/heartbeat") ~> epRoutes ~> check {
            status shouldBe OK
            val parsedResponse = responseAs[String]
            parsedResponse should include("GNR-129")
            parsedResponse should include("Listening")
          }
        }
      }

      "should respond with status code GNR-130 when Draining" taggedAs UNSAFE_IgnoreLog in {
        // Set app state to Degraded
        publishAppStateEvent(ErrorEvent(MildSystemError, CONTEXT_GENERAL, new RuntimeException("test"), Option("test")))

        buildGetReq("/agency/heartbeat") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] should include ("GNR-129")
        }

        // Set app state to Draining
        platform.appStateHandler.startBeforeServiceUnbindTask()
        publishAppStateEvent(StartDraining)
        publishAppStateEvent(ErrorEvent(DrainingStarted, CONTEXT_GENERAL, new RuntimeException("test"), Option("test")))

        buildGetReq("/agency/heartbeat") ~> epRoutes ~> check {
          status shouldBe ServiceUnavailable
          responseAs[String] should include ("GNR-130")
        }
      }
    }
  }

}
