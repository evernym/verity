package com.evernym.verity.http.base.open

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Type`}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.model.ContentTypes._
import akka.util.ByteString
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.http.route_handlers.open.{RestAcceptedResponse, RestErrorResponse, RestOKResponse}
import com.evernym.verity.util.Base58Util
import com.evernym.verity.vault.KeyParam
import com.evernym.verity.Status
import com.evernym.verity.actor.wallet.SignMsg
import org.json.JSONObject

trait RestApiSpec { this : EndpointHandlerBaseSpec =>

  def testAgentRestApiUsage(): Unit = {
    lazy val payload = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/write-schema/0.6/write","@id":"${UUID.randomUUID.toString}","name":"schema-name","version":"1.0","attrNames":[]}""")
    lazy val createConnectionPayload = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/connecting/0.6/CREATE_CONNECTION","@id":"${UUID.randomUUID.toString}","sourceId": "${UUID.randomUUID.toString}","includePublicDID": false}""")
    lazy val routingDid = mockEdgeAgent.myDIDDetail.did
    lazy val verKey = mockEdgeAgent.myDIDDetail.verKey
    lazy val signature = {
      val signedVerKey = mockEdgeAgent.walletAPI.signMsg(SignMsg(KeyParam(Left(verKey)), verKey.getBytes))(mockEdgeAgent.wap)
      Base58Util.encode(signedVerKey)
    }

    "when sent rest api request msg on disabled api" - {
      "should respond with NotImplemented" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = false
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe NotImplemented
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.NOT_IMPLEMENTED.statusCode, Status.NOT_IMPLEMENTED.statusMsg)
        }
      }
    }

    "when sent rest api request msg without authorization header" - {
      "should respond with Unauthorized" in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
        ) ~> epRoutes ~> check {
          status shouldBe Unauthorized
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
        }
      }
    }

    "when sent rest api request msg with authorization header without ':'" - {
      "should respond with Unauthorized" in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", "invalidHeader"))
        ) ~> epRoutes ~> check {
          status shouldBe Unauthorized
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
        }
      }
    }

    "when sent rest api request msg with authorization header with too many ':'" - {
      "should respond with Unauthorized" in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", "invalid:Head:er"))
        ) ~> epRoutes ~> check {
          status shouldBe Unauthorized
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
        }
      }
    }

    "when sent rest api request msg with invalid authorisation verkey" - {
      "should respond with Unauthorized" in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", s"BXaNZkPPGE7rWSNLif16C8JNGhLRxU44HkhWQQhkj32P:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe Unauthorized
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
        }
      }
    }

    "when sent rest api request msg with invalid authorisation signature" - {
      "should respond with Unauthorized" in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", s"$verKey:SbvE1CrMvEMueHsgfQA6ayTqjfpFUGboGuv6hSJru1v8kDZ4nrJP4SpVfBk7ub6f7SAA3eQmh6gWZBdub8XU9JP"))
        ) ~> epRoutes ~> check {
          status shouldBe Unauthorized
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
        }
      }
    }

    "when sent valid rest api request msg" - {
      "should respond with Accepted" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe Accepted
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
        }
      }
    }

    "when sent valid rest api request msg without threadId" - {
      "should respond with Accepted" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe Accepted
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
        }
      }
    }

    "when sent valid rest api request msg with case insensitive auth header" - {
      "should respond with Accepted" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("x-aPi-KeY", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe Accepted
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
        }
      }
    }

    "when sent rest api msg with wrong protocol family name" - {
      "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema-wrong/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe BadRequest
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid protocol family and/or version")
        }
      }
    }

    "when sent rest api msg with wrong protocol version" - {
      "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
        buildPostReq(s"/api/$routingDid/write-schema/0.0/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe BadRequest
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid protocol family and/or version")
        }
      }
    }

    "when sent rest api msg with invalid JSON payload" - {
      "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, ByteString("{\"test")),
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe BadRequest
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid payload")
        }
      }
    }

    "when sent rest api msg with invalid JSON payload - added comma" - {
      "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/write-schema/0.6/write","@id":"${UUID.randomUUID.toString}",}""")),
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe BadRequest
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid payload")
        }
      }
    }

    "when sent rest api msg without @type in JSON payload" - {
      "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        buildPostReq(s"/api/$routingDid/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, ByteString("{\"@typeeee\": \"something\"}")),
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe BadRequest
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid payload")
        }
      }
    }

    "when sent rest api msg with invalid route" - {
      "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        buildPostReq(s"/api/invalid-route/write-schema/0.6/${UUID.randomUUID.toString}",
          HttpEntity.Strict(ContentTypes.`application/json`, payload),
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe BadRequest
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.INVALID_VALUE.statusCode, "Route is not a base58 string")
        }
      }
    }

    "when sent valid get rest api request msg" - {
      "should respond with OK" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        val threadId = UUID.randomUUID.toString
        val sourceId = UUID.randomUUID.toString
        val expectedJsonConnectingStatus = Map[String, Any](
          "@type" -> "did:sov:123456789abcdefghi1234;spec/connecting/0.6/status-report",
          "status" -> "INITIALIZED",
          "sourceId" -> sourceId,
        )
        buildGetReq(s"/api/$routingDid/connecting/0.6/$threadId?sourceId=$sourceId",
          Seq(RawHeader("X-API-key", s"$verKey:$signature"))
        ) ~> epRoutes ~> check {
          status shouldBe OK
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          val actualConnectingStatus = responseTo[RestOKResponse].result.asInstanceOf[Map[String, Any]]
          Set("@type", "status", "sourceId").foreach { fieldName =>
            expectedJsonConnectingStatus.get(fieldName) shouldBe actualConnectingStatus.get(fieldName)
          }
          actualConnectingStatus.get("~thread").map(s => s.asInstanceOf[Map[String, Any]].get("thId")).contains(Some(0))
          actualConnectingStatus.contains("@id") shouldBe true
        }
      }
    }

    "when sent connecting 0.6 CREATE_CONNECTION request" - {
      "should respond with normal and truncated invite" taggedAs UNSAFE_IgnoreLog in {
        overrideRestEnable = true
        val (_, lastPayload) = withExpectNewRestMsgAtRegisteredEndpoint({
          buildPostReq(s"/api/$routingDid/connecting/0.6/",
            HttpEntity.Strict(ContentTypes.`application/json`, createConnectionPayload),
            Seq(RawHeader("X-API-key", s"$verKey:$signature"))
          ) ~> epRoutes ~> check {
            status shouldBe Accepted
            header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
            responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
          }
        })
        lastPayload.isDefined shouldBe true
        val jsonMsgString = lastPayload.get

        val jsonMsg = new JSONObject(jsonMsgString)
        val regInvite = jsonMsg.getJSONObject("inviteDetail")
        val abrInvite = jsonMsg.getJSONObject("truncatedInviteDetail")

        regInvite.getString("connReqId") shouldBe abrInvite.getString("id")
        regInvite.getString("targetName") shouldBe abrInvite.getString("t")
        regInvite.getString("statusCode") shouldBe abrInvite.getString("sc")
        regInvite.getString("statusMsg") shouldBe abrInvite.getString("sm")
        regInvite.getString("version") shouldBe abrInvite.getString("version")

        // senderAgencyDetail
        val regSAD = regInvite.getJSONObject("senderAgencyDetail")
        val abrSAD = abrInvite.getJSONObject("sa")

        regSAD.getString("DID") shouldBe abrSAD.getString("d")
        regSAD.getString("verKey") shouldBe abrSAD.getString("v")
        regSAD.getString("endpoint") shouldBe abrSAD.getString("e")

        // senderDetail
        val regSD = regInvite.getJSONObject("senderDetail")
        val abrSD = abrInvite.getJSONObject("s")

        regSD.getString("DID") shouldBe abrSD.getString("d")
        regSD.getString("verKey") shouldBe abrSD.getString("v")
        regSD.getString("name") shouldBe abrSD.getString("n")
        regSD.getString("logoUrl") shouldBe abrSD.getString("l")

        // senderDetail.agentKeyDlgProof
        val regDP = regSD.getJSONObject("agentKeyDlgProof")
        val abrDP = abrSD.getJSONObject("dp")

        regDP.getString("agentDID") shouldBe abrDP.getString("d")
        regDP.getString("agentDelegatedKey") shouldBe abrDP.getString("k")
        regDP.getString("signature") shouldBe abrDP.getString("s")
      }
    }

  }
}
