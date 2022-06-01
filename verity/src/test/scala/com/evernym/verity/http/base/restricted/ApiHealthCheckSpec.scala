package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes.{OK, ServiceUnavailable}
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.http.route_handlers.open.models.ReadinessStatus
import com.evernym.verity.util.healthcheck.{ApiStatus, HealthChecker}
import org.mockito.MockitoSugar.when

import scala.concurrent.Future


class MockHealthChecker extends HealthChecker{
  override def checkAkkaStorageStatus: Future[ApiStatus] = Future.successful(ApiStatus(true, "OK"))

  override def checkWalletStorageStatus: Future[ApiStatus] = Future.successful(ApiStatus(true, "OK"))

  override def checkBlobStorageStatus: Future[ApiStatus] = Future.successful(ApiStatus(true, "OK"))

  override def checkLiveness: Future[Unit] = Future.successful((): Unit)

  override def checkLedgerPoolStatus: Future[ApiStatus] = Future.successful(ApiStatus(true, "OK"))

  override def checkVDRToolsStatus: Future[ApiStatus] = Future.successful(ApiStatus(true, "OK"))
}

trait ApiHealthCheckSpec {this: EdgeEndpointBaseSpec =>

  def testBaseApiHeathCheck(): Unit = {
    "when sent req to /verity/node/readiness and event journal and wallet storage services ready" - {
      "should be return 200 OK" in {
        when(appStateCoordinator.isDrainingStarted).thenReturn(false)
        when(healthChecker.checkAkkaStorageStatus).thenReturn(Future.successful(ApiStatus(true, "OK")))
        when(healthChecker.checkWalletStorageStatus).thenReturn(Future.successful(ApiStatus(true, "OK")))
        when(healthChecker.checkBlobStorageStatus).thenReturn(Future.successful(ApiStatus(true, "OK")))
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
            status shouldBe OK
            responseTo[ReadinessStatus] shouldBe ReadinessStatus(true, "OK", "OK", "OK")
          }
        }
      }
    }

    "when sent req to /verity/node/readiness and only event journal isn't responding" - {
      "should be return 503 Unavailable" in {
        when(healthChecker.checkAkkaStorageStatus).thenReturn(Future.successful(ApiStatus(false, "Something bad")))
        when(healthChecker.checkWalletStorageStatus).thenReturn(Future.successful(ApiStatus(true, "OK")))
        when(healthChecker.checkBlobStorageStatus).thenReturn(Future.successful(ApiStatus(true, "OK")))
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
          status shouldBe ServiceUnavailable
          responseTo[ReadinessStatus] shouldBe ReadinessStatus(false, "Something bad", "OK", "OK")
        }
      }
    }

    "when sent req to /verity/node/readiness and only wallet storage isn't responding" - {
      "should be return 503 Unavailable" in {
        when(healthChecker.checkAkkaStorageStatus).thenReturn(Future.successful(ApiStatus(true, "OK")))
        when(healthChecker.checkWalletStorageStatus).thenReturn(Future.successful(ApiStatus(false, "BAD")))
        when(healthChecker.checkBlobStorageStatus).thenReturn(Future.successful(ApiStatus(true, "OK")))
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
          status shouldBe ServiceUnavailable
          responseTo[ReadinessStatus] shouldBe ReadinessStatus(false, "OK", "BAD", "OK")
        }
      }
    }

  "when sent req to /verity/node/readiness and event journal and wallet storage isn't responding" - {
    "should be return 503 Unavailable" in {
      when(healthChecker.checkAkkaStorageStatus).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      when(healthChecker.checkWalletStorageStatus).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      when(healthChecker.checkBlobStorageStatus).thenReturn(Future.successful(ApiStatus(true, "OK")))
      buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
        status shouldBe ServiceUnavailable
        responseTo[ReadinessStatus] shouldBe ReadinessStatus(false, "BAD", "BAD", "OK")
      }
    }
  }

  "when sent req to /verity/node/readiness and event journal, wallet storage and storage api isn't responding" - {
    "should be return 503 Unavailable" in {
      when(healthChecker.checkAkkaStorageStatus).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      when(healthChecker.checkWalletStorageStatus).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      when(healthChecker.checkBlobStorageStatus).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
        status shouldBe ServiceUnavailable
        responseTo[ReadinessStatus] shouldBe ReadinessStatus(false, "BAD", "BAD", "BAD")
      }
    }
  }

  "when sent req to /verity/node/liveness and all it's ok" - {
    "should be return 200 OK" in {
      when(healthChecker.checkLiveness).thenReturn(Future.successful((): Unit))
      buildGetReq("/verity/node/liveness") ~> epRoutes ~> check {
        status shouldBe OK
        responseAs[String] shouldBe "OK"
      }
    }
  }

  "when sent req to /verity/node/liveness and verity is not 'healthy'" - {
    "should be return 503 Unavailable" in {
      when(healthChecker.checkLiveness).thenReturn(Future.failed(new Exception("BAD")))
      buildGetReq("/verity/node/liveness") ~> epRoutes ~> check {
        status shouldBe ServiceUnavailable
        responseAs[String] shouldBe "BAD"
      }
    }
  }

}
