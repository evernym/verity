package com.evernym.verity.http.rest

import java.util.UUID
import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{RawHeader, `Content-Type`}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.util.ByteString
import com.evernym.verity.Status
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.http.rest.base.RestApiBaseSpec
import com.evernym.verity.http.route_handlers.open.{RestAcceptedResponse, RestErrorResponse, RestOKResponse, `API-REQUEST-ID`}
import com.evernym.verity.metrics.MetricsWriter

/**
 * Purpose of this spec is to test the rest api infrastructure in general
 * with positive and negative scenarios etc
 */
class BasicRestApiSpec
  extends RestApiBaseSpec {

  lazy val createSchemaPayload: ByteString = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/write-schema/0.6/write","@id":"${UUID.randomUUID.toString}","name":"schema-name","version":"1.0","attrNames":["first_name","last_name"]}""")
  lazy val createConnectionPayload: ByteString = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/connecting/0.6/CREATE_CONNECTION","@id":"${UUID.randomUUID.toString}","sourceId": "${UUID.randomUUID.toString}","includePublicDID": false}""")

  "when performed prerequisite setup" - {
    "should be successful" - {
      testSetAppStateAsListening()
      testCheckAppStateIsListening()
      testAgencySetup()
      testAgentProvisioning(mockEntEdgeEnv)
      testUpdateComMethod(mockEntEdgeEnv)
    }
  }

  "when sent update [update-configs 0.6] rest api request msg" - {
    "should respond with Accepted" in {
      val updateConfigs: ByteString = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/update-configs/0.6/update","@id":"${UUID.randomUUID.toString}","configs":[{"name": "ent-name"},{"logoUrl": "ent-logo-url"}]}""")

      buildPostReq(s"/api/${mockEntRestEnv.myDID}/update-configs/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, updateConfigs),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe Accepted
      }
    }
  }

  "when sent get-status [update-configs 0.6] rest api request msg" - {
    "should respond with OK" in {
      buildGetReq(s"/api/${mockEntRestEnv.myDID}/update-configs/0.6/${UUID.randomUUID.toString}",
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe OK
      }
    }
  }

  "when sent write-schema rest api request msg on disabled api" - {
    "should respond with NotImplemented" taggedAs UNSAFE_IgnoreLog in withRestApiDisabled {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe NotImplemented
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.NOT_IMPLEMENTED.statusCode, Status.NOT_IMPLEMENTED.statusMsg)
      }
    }
  }

  "when sent write-schema rest api request msg without authorization header" - {
    "should respond with Unauthorized" in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
      ) ~> epRoutes ~> check {
        status shouldBe Unauthorized
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
      }
    }
  }

  "when sent write-schema rest api request msg with authorization header without ':'" - {
    "should respond with Unauthorized" in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("X-API-key", "invalidHeader"))
      ) ~> epRoutes ~> check {
        status shouldBe Unauthorized
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
      }
    }
  }

  "when sent write-schema rest api request msg with authorization header with too many ':'" - {
    "should respond with Unauthorized" in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("X-API-key", "invalid:Head:er"))
      ) ~> epRoutes ~> check {
        status shouldBe Unauthorized
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
      }
    }
  }

  "when sent write-schema rest api request msg with invalid authorisation verkey" - {
    "should respond with Unauthorized" in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("X-API-key", s"BXaNZkPPGE7rWSNLif16C8JNGhLRxU44HkhWQQhkj32P:${mockEntRestEnv.myDIDSignature}"))
      ) ~> epRoutes ~> check {
        status shouldBe Unauthorized
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
      }
    }
  }

  "when sent write-schema rest api request msg with invalid authorisation signature" - {
    "should respond with Unauthorized" in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("X-API-key", s"${{mockEntRestEnv.myDIDVerKey}}:SbvE1CrMvEMueHsgfQA6ayTqjfpFUGboGuv6hSJru1v8kDZ4nrJP4SpVfBk7ub6f7SAA3eQmh6gWZBdub8XU9JP"))
      ) ~> epRoutes ~> check {
        status shouldBe Unauthorized
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.UNAUTHORIZED.statusCode, Status.UNAUTHORIZED.statusMsg)
      }
    }
  }

  "when sent valid issuer setup rest api request msg" - {
    "should respond with Accepted" taggedAs UNSAFE_IgnoreLog in {
      performIssuerSetup(mockEntRestEnv)
    }
  }

  "when sent valid write schema rest api request msg" - {
    "should respond with Accepted" taggedAs UNSAFE_IgnoreLog in {
      performWriteSchema(mockEntRestEnv, createSchemaPayload)
    }
  }

  "when sent valid write schema rest api request msg without threadId" - {
    "should respond with Accepted" taggedAs UNSAFE_IgnoreLog in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe Accepted
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
      }
    }
  }

  "when sent valid write schema rest api request msg with case insensitive auth header" - {
    "should respond with Accepted" taggedAs UNSAFE_IgnoreLog in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("x-aPi-KeY", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe Accepted
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
      }
    }
  }

  "when sent API-REQUEST-ID header" - {
    "should respond with the same API-REQUEST-ID header" taggedAs UNSAFE_IgnoreLog in {
      val reqId = UUID.randomUUID().toString
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(
          RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"),
          RawHeader("API-REQUEST-ID", reqId)
        )
      ) ~> epRoutes ~> check {
        status shouldBe Accepted
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        header("API-REQUEST-ID") shouldEqual Some(`API-REQUEST-ID`(reqId))
        responseTo[RestAcceptedResponse] shouldBe RestAcceptedResponse()
      }
    }
  }

  "when sent write schema rest api msg with wrong protocol family name" - {
    "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema-wrong/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid protocol family and/or version")
      }
    }
  }

  "when sent write schema rest api msg with wrong protocol version" - {
    "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.0/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid protocol family and/or version")
      }
    }
  }

  "when sent write schema rest api msg with invalid JSON payload" - {
    "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, ByteString("{\"test")),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid payload")
      }
    }
  }

  "when sent write schema rest api msg with invalid JSON payload - added comma" - {
    "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/write-schema/0.6/write","@id":"${UUID.randomUUID.toString}",}""")),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid payload")
      }
    }
  }

  "when sent write schema rest api msg without @type in JSON payload" - {
    "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, ByteString("{\"@typeeee\": \"something\"}")),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.VALIDATION_FAILED.statusCode, "Invalid payload")
      }
    }
  }

  "when sent write schema rest api msg without required field in JSON payload" - {
    "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
      lazy val payload: ByteString = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/write-schema/0.6/write","@id":"${UUID.randomUUID.toString}"}""")
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, payload),
        Seq(RawHeader("x-aPi-KeY", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.MISSING_REQ_FIELD.statusCode, "required attribute not found (missing/empty/null): 'name'")
      }
    }
  }

  "when sent write schema rest api msg with invalid route" - {
    "should respond with BadRequest" taggedAs UNSAFE_IgnoreLog in {
      buildPostReq(s"/api/invalid-route/write-schema/0.6/${UUID.randomUUID.toString}",
        HttpEntity.Strict(ContentTypes.`application/json`, createSchemaPayload),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
        responseTo[RestErrorResponse] shouldBe RestErrorResponse(Status.INVALID_VALUE.statusCode, "Route is not a base58 string")
      }
    }
  }

  "when sent valid get rest api request msg" - {
    "should respond with OK" taggedAs UNSAFE_IgnoreLog in {
      val threadId = UUID.randomUUID.toString
      val sourceId = UUID.randomUUID.toString
      val expectedJsonConnectingStatus = Map[String, Any](
        "@type" -> "did:sov:123456789abcdefghi1234;spec/connecting/0.6/status-report",
        "status" -> "INITIALIZED",
        "sourceId" -> sourceId,
      )
      buildGetReq(s"/api/${mockEntRestEnv.myDID}/connecting/0.6/$threadId?sourceId=$sourceId",
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
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

  "when sent connecting 0.6 CREATE_CONNECTION request with invalid message family detail" - {
    "should respond with not found error" in {
      val invalidPayload = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/connecting/0.6/1?1%20and%203614%3d3623=1","@id":"${UUID.randomUUID.toString}","sourceId": 1,"includePublicDID": "FAAAAAAAAAAAAAAAAA"}""")
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/connecting/0.6/",
        HttpEntity.Strict(ContentTypes.`application/json`, invalidPayload),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe NotFound
      }
    }
  }

  "when sent connecting 0.6 CREATE_CONNECTION request with malicious payload 1" - {
    "should respond with not found error" in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/connecting/0.6",
        HttpEntity.Strict(ContentTypes.`application/json`, ByteString("null")),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        responseTo[RestErrorResponse] shouldBe RestErrorResponse("GNR-101", "Invalid payload")
      }
    }
  }

  "when sent connecting 0.6 CREATE_CONNECTION request with malicious payload 2" - {
    "should respond with bad request" in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/connecting/0.6",
        HttpEntity.Strict(ContentTypes.`application/json`, ByteString("")),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        responseTo[RestErrorResponse] shouldBe RestErrorResponse("GNR-101", "Invalid payload")
      }
    }
  }

  "when sent connecting 0.6 CREATE_CONNECTION request with malicious payload 3" - {
    "should respond with bad request" in {
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/connecting/0.6",
        HttpEntity.Strict(ContentTypes.`application/json`, ByteString("""{"test"}""")),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        responseTo[RestErrorResponse] shouldBe RestErrorResponse("GNR-101", "Invalid payload")
      }
    }
  }

  "when sent connecting 0.6 CREATE_CONNECTION request with malicious payload 4" - {
    "should respond with bad request" in {
      val payload: ByteString = ByteString(s"""{"@type":"did:sov:123456789abcdefghi1234;spec/connecting/0.6/CREATE_CONNECTION","@id":"${UUID.randomUUID.toString}","includePublicDID": false}""")
      buildPostReq(s"/api/${mockEntRestEnv.myDID}/connecting/0.6",
        HttpEntity.Strict(ContentTypes.`application/json`, payload),
        Seq(RawHeader("X-API-key", s"${mockEntRestEnv.myDIDApiKey}"))
      ) ~> epRoutes ~> check {
        status shouldBe BadRequest
        responseTo[RestErrorResponse] shouldBe RestErrorResponse("GNR-117", "required attribute not found (missing/empty/null): 'sourceId'")
      }
    }
  }

  "when sent connecting 0.6 CREATE_CONNECTION request" - {
    "should respond with normal and truncated invite" taggedAs UNSAFE_IgnoreLog in {
      createConnectionRequest(mockEntRestEnv, "conn1", createConnectionPayload)
    }
  }

}
