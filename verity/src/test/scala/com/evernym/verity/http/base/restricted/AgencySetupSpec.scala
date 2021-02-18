package com.evernym.verity.http.base.restricted

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.http.base.EdgeEndpointBaseSpec

trait AgencySetupSpec { this : EdgeEndpointBaseSpec =>

  def testAgencySetup(): Unit = {
    "Agency admin" - {

      "when sent create key api call" - {
        "should respond with created key detail" taggedAs UNSAFE_IgnoreLog in {
          buildPostReq(s"$agencySetupUrlPathPrefix/key") ~> epRoutes ~> check {
            status shouldBe OK
            responseTo[AgencyPublicDid]
          }
        }
      }

      "when sent create key api again" - {
        "should respond with forbidden response" in {
          buildPostReq(s"$agencySetupUrlPathPrefix/key") ~> epRoutes ~> check {
            status shouldBe Forbidden
          }
        }
      }

      "when sent set endpoint api" - {
        "should respond with success" in {
          buildPostReq(s"$agencySetupUrlPathPrefix/endpoint") ~> epRoutes ~> check {
            status shouldBe OK
          }
        }
      }

      "when sent set endpoint api again" - {
        "should respond with error for repeated setup agency endpoint call" in {
          buildPostReq(s"$agencySetupUrlPathPrefix/endpoint") ~> epRoutes ~> check {
            status shouldBe Forbidden
          }
        }
      }

      "when sent get agency key" - {
        "should respond with created key detail" taggedAs (UNSAFE_IgnoreLog) in {
          buildGetReq(s"/agency") ~> epRoutes ~> check {
            status shouldBe OK
            val apd = responseTo[AgencyPublicDid]
            apd.didPair.validate()
          }
        }
      }
    }
  }
}
