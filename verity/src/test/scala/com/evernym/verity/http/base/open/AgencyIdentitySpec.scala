package com.evernym.verity.http.base.open

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.http.base.EdgeEndpointBaseSpec
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.util.Util

trait AgencyIdentitySpec { this : EdgeEndpointBaseSpec =>

  def testAgencyIdentityAndDetails(): Unit = {

    "when sent get agency identity api call" - {
      "should respond with an AgencyIdentity message" in {
        buildGetReq("/agency") ~> epRoutes ~> check {
          status shouldBe OK
          val resp: AgencyPublicDid = responseTo[AgencyPublicDid]
          hasValidDIDVerKeyPair(resp.DID, resp.verKey)
        }
      }
    }
    "when sent get agency identity api call requesting details" - {
      "should respond with an AgencyDetail message" in {
        buildGetReq("/agency?detail=Y") ~> epRoutes ~> check {
          status shouldBe OK
          val resp:AgencyPublicDid = responseTo[AgencyPublicDid]
          hasValidDIDVerKeyPair(resp.DID, resp.verKey)
          resp.ledgers.getOrElse(List.empty).headOption.get("name") shouldBe "default"
        }
      }
    }

    def hasValidDIDVerKeyPair(did: DID, verKey: VerKey): Unit = {
      try {
        Util.checkIfDIDIsValid(did)
        Util.checkIfDIDBelongsToVerKey(did, verKey)
      } catch {
        case e: Exception => fail(s"Must include a valid DID/verKey pair. Details: $e" )
      }
    }
  }
}
