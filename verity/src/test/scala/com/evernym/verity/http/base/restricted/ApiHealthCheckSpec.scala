package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes.{OK, ServiceUnavailable}
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.http.route_handlers.restricted.ApiStatus
import org.mockito.MockitoSugar.when

import scala.concurrent.Future

trait ApiHealthCheckSpec {this: EdgeEndpointBaseSpec =>

  def testBaseApiHeathCheck(): Unit = {
    "when sent req to /verity/node/readiness and event journal and wallet storage services ready" - {
      "should be return 200 OK" in {
        when(apiHealthCheck.checkAkkaEventStorageReadiness).thenReturn(Future.successful(true, "OK"))
        when(apiHealthCheck.checkWalletStorageReadiness).thenReturn(Future.successful(true, "OK"))
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
          status shouldBe OK
          responseTo[ApiStatus] shouldBe ApiStatus("OK", "OK")
        }
        }
      }
    }

    "when sent req to /verity/node/readiness and only event journal isn't responding" - {
      "should be return 503 Unavailable" in {
        when(apiHealthCheck.checkAkkaEventStorageReadiness).thenReturn(Future.successful(false, "Something bad"))
        when(apiHealthCheck.checkWalletStorageReadiness).thenReturn(Future.successful(true, "OK"))
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
          status shouldBe ServiceUnavailable
          responseTo[ApiStatus] shouldBe ApiStatus(rds = "Something bad", dynamoDB = "OK")
        }
      }
    }

    "when sent req to /verity/node/readiness and only wallet storage isn't responding" - {
      "should be return 503 Unavailable" in {
        when(apiHealthCheck.checkAkkaEventStorageReadiness).thenReturn(Future.successful(true, "OK"))
        when(apiHealthCheck.checkWalletStorageReadiness).thenReturn(Future.successful(false, "BAD"))
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
          status shouldBe ServiceUnavailable
          responseTo[ApiStatus] shouldBe ApiStatus(rds = "OK", dynamoDB = "BAD")
        }
      }
    }

  "when sent req to /verity/node/readiness and event journal and wallet storage isn't responding" - {
    "should be return 503 Unavailable" in {
      when(apiHealthCheck.checkAkkaEventStorageReadiness).thenReturn(Future.successful(false, "BAD"))
      when(apiHealthCheck.checkWalletStorageReadiness).thenReturn(Future.successful(false, "BAD"))
      buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
        status shouldBe ServiceUnavailable
        responseTo[ApiStatus] shouldBe ApiStatus(rds = "BAD", dynamoDB = "BAD")
      }
    }
  }

  "when sent req to /verity/node/liveness" - {
    "should be return 200 OK" in {
      buildGetReq("/verity/node/liveness") ~> epRoutes ~> check {
        status shouldBe OK
      }
    }
  }

}
