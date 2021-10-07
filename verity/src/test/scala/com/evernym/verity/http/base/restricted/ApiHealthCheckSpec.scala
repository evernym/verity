package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes.{OK, ServiceUnavailable}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.evernym.verity.actor.Platform
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.http.route_handlers.restricted.{AbstractHealthChecker, ApiStatus, ReadinessStatus}
import org.mockito.MockitoSugar.when

import scala.concurrent.Future


class MockHealthChecker extends AbstractHealthChecker{
  override def checkAkkaEventStorageReadiness: Future[ApiStatus] = Future.successful(ApiStatus(true, "OK"))

  override def checkWalletStorageReadiness: Future[ApiStatus] = Future.successful(ApiStatus(true, "OK"))

  override def checkStorageAPIReadiness: Future[ApiStatus] = Future.successful(ApiStatus(true, "OK"))

  override def checkLiveness: Future[Unit] = Future.successful((): Unit)
}

trait ApiHealthCheckSpec {this: EdgeEndpointBaseSpec =>

  def testBaseApiHeathCheck(): Unit = {
    "when sent req to /verity/node/readiness and event journal and wallet storage services ready" - {
      "should be return 200 OK" in {
        when(healthChecker.checkAkkaEventStorageReadiness).thenReturn(Future.successful(ApiStatus(true, "OK")))
        when(healthChecker.checkWalletStorageReadiness).thenReturn(Future.successful(ApiStatus(true, "OK")))
        when(healthChecker.checkStorageAPIReadiness).thenReturn(Future.successful(ApiStatus(true, "OK")))
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
          status shouldBe OK
          responseTo[ReadinessStatus] shouldBe ReadinessStatus("OK", "OK", "OK")
        }
        }
      }
    }

    "when sent req to /verity/node/readiness and only event journal isn't responding" - {
      "should be return 503 Unavailable" in {
        when(healthChecker.checkAkkaEventStorageReadiness).thenReturn(Future.successful(ApiStatus(false, "Something bad")))
        when(healthChecker.checkWalletStorageReadiness).thenReturn(Future.successful(ApiStatus(true, "OK")))
        when(healthChecker.checkStorageAPIReadiness).thenReturn(Future.successful(ApiStatus(true, "OK")))
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
          status shouldBe ServiceUnavailable
          responseTo[ReadinessStatus] shouldBe ReadinessStatus("Something bad", "OK", "OK")
        }
      }
    }

    "when sent req to /verity/node/readiness and only wallet storage isn't responding" - {
      "should be return 503 Unavailable" in {
        when(healthChecker.checkAkkaEventStorageReadiness).thenReturn(Future.successful(ApiStatus(true, "OK")))
        when(healthChecker.checkWalletStorageReadiness).thenReturn(Future.successful(ApiStatus(false, "BAD")))
        when(healthChecker.checkStorageAPIReadiness).thenReturn(Future.successful(ApiStatus(true, "OK")))
        buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
          status shouldBe ServiceUnavailable
          responseTo[ReadinessStatus] shouldBe ReadinessStatus("OK", "BAD", "OK")
        }
      }
    }

  "when sent req to /verity/node/readiness and event journal and wallet storage isn't responding" - {
    "should be return 503 Unavailable" in {
      when(healthChecker.checkAkkaEventStorageReadiness).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      when(healthChecker.checkWalletStorageReadiness).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      when(healthChecker.checkStorageAPIReadiness).thenReturn(Future.successful(ApiStatus(true, "OK")))
      buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
        status shouldBe ServiceUnavailable
        responseTo[ReadinessStatus] shouldBe ReadinessStatus("BAD", "BAD", "OK")
      }
    }
  }

  "when sent req to /verity/node/readiness and event journal, wallet storage and storage api isn't responding" - {
    "should be return 503 Unavailable" in {
      when(healthChecker.checkAkkaEventStorageReadiness).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      when(healthChecker.checkWalletStorageReadiness).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      when(healthChecker.checkStorageAPIReadiness).thenReturn(Future.successful(ApiStatus(false, "BAD")))
      buildGetReq("/verity/node/readiness") ~> epRoutes ~> check {
        status shouldBe ServiceUnavailable
        responseTo[ReadinessStatus] shouldBe ReadinessStatus("BAD", "BAD", "BAD")
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
