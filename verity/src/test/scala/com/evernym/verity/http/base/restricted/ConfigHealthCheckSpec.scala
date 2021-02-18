package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.http.base.EdgeEndpointBaseSpec


trait ConfigHealthCheckSpec { this : EdgeEndpointBaseSpec =>

  def testConfigHealthCheck(): Unit = {

    "when sent get config api call" - {
      "should respond with whole config" in {
        buildGetReq("/agency/internal/health-check/config") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] should include("agency")
        }
      }
    }

    "when sent get config api call with a path (with OBJECT type json value)" -{
      "should respond with corresponding config value" in {
        buildGetReq("/agency/internal/health-check/config?path=verity.resource-usage-rules.usage-rules.default") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] should include ("endpoint")
        }
      }
    }

    "when sent get config api call with a path (with NUMBER type json value)" - {
      "should respond with corresponding config value" in {
        buildGetReq("/agency/internal/health-check/config?path=verity.wallet-storage.host-port") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] should include("3306")
        }
      }
    }

    "when sent get config api call with a path (with STRING type json value)" - {
      "should respond with corresponding config value" in {
        buildGetReq("/agency/internal/health-check/config?path=akka.actor.provider") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] should include("akka.cluster.ClusterActorRefProvider")
        }
      }
    }

    "when sent get config api call with a path (with BOOLEAN type json value)" - {
      "should respond with corresponding config value" in {
        buildGetReq("/agency/internal/health-check/config?path=verity.resource-usage-rules.apply-usage-rules") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] should include("true")
        }
      }
    }

    "when sent get config api call with a path (with LIST type json value)" - {
      "should respond with corresponding config value" in {
        buildGetReq("/agency/internal/health-check/config?path=verity.internal-api.allowed-from-ip-addresses") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] should include("127.0.0.1/32")
        }
      }
    }

    "when sent get config api call with a path which doesn't exists" - {
      "should respond successfully" in {
        buildGetReq("/agency/internal/health-check/config?path=verity.resource-usage-rules.apply-usage-rul") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] should include("no config found at give path")
        }
      }
    }
  }
}
