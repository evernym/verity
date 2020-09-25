package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.http.base.EndpointHandlerBaseSpec

trait ReloadConfigSpec { this : EndpointHandlerBaseSpec =>

  def testReloadConfig(): Unit = {
    "when sent reload config api call" - {
      "should reload config " in {
        buildPutReq("/agency/internal/maintenance/config/reload") ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }
  }

}
