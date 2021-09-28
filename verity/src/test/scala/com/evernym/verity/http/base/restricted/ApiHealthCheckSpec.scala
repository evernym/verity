package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes.OK
import com.evernym.verity.http.base.EdgeEndpointBaseSpec

trait ApiHealthCheckSpec {this: EdgeEndpointBaseSpec =>
  def testBaseApiHeathCheck(): Unit = {
    "when sent req to /verity/node/readiness" - {
      "should be return 200 OK" in {
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

    "when sent req to /verity/node/healthy" - {
      "should be return 200 OK" in {
        buildGetReq("/verity/node/healthy") ~> epRoutes ~> check {
          status shouldBe OK
        }
      }
    }

  }
}
