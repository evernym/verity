package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.actor.agent.maintenance.ManagerStatus
import com.evernym.verity.http.base.EdgeEndpointBaseSpec

trait ActorStateCleanupHealthCheckSpec { this : EdgeEndpointBaseSpec =>

  def testAgentRouteFixStatus(): Unit = {
    "when sent check agent route fix status GET api" - {
      "should respond with ok" in {
        buildGetReq("/agency/internal/maintenance/actor-state-cleanup/status") ~> epRoutes ~> check {
          status shouldBe OK
          responseTo[ManagerStatus]
        }
      }
    }
  }
}
