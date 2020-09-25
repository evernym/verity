package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.apphealth.AppStateConstants.CONTEXT_GENERAL
import com.evernym.verity.apphealth.state.{DegradedState, DrainingState}
import com.evernym.verity.apphealth._
import com.evernym.verity.http.base.EndpointHandlerBaseSpec

trait HeartbeatSpec { this : EndpointHandlerBaseSpec =>

  def testHeartbeat(): Unit = {

    "when sent get heartbeat api call" - {
      "should respond with status code GNR-129 indicating the Agency is accepting traffic" taggedAs (UNSAFE_IgnoreLog) in {

        switchAppToListeningSate()

        buildGetReq("/agency/heartbeat") ~> epRoutes ~> check {
          status shouldBe OK
          val parsedResponse = responseAs[String]
          parsedResponse should include ("GNR-129")
          parsedResponse should include ("Listening")
        }
      }
      "should respond with status code GNR-130 when Draining" taggedAs (UNSAFE_IgnoreLog) in {
        // Set app state to Draining
        AppStateManager.performTransition(DrainingState, EventParam(
          DrainingStarted, CONTEXT_GENERAL,
          causeDetail=CauseDetail("test", "test"),
          system = Option(system)
        ))

        buildGetReq("/agency/heartbeat") ~> epRoutes ~> check {
          status shouldBe ServiceUnavailable
          responseAs[String] should include ("GNR-130")
        }

        // Set app state back to Degraded
        AppStateManager.performTransition(DegradedState, EventParam(
          MildSystemError, CONTEXT_GENERAL,
          causeDetail=CauseDetail("test", "test"),
          system = Option(system)
        ))

        buildGetReq("/agency/heartbeat") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] should include ("GNR-129")
        }
      }
    }
  }

}
