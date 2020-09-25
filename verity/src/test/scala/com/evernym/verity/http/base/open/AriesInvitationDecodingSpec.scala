package com.evernym.verity.http.base.open

import java.net.InetAddress

import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.StatusCodes.{BadRequest, OK}
import akka.http.scaladsl.model.headers.{`Content-Type`, `X-Forwarded-For`}
import com.evernym.verity.http.base.EndpointHandlerBaseSpec
import com.evernym.verity.util.Base64Util
import org.json.JSONObject

trait AriesInvitationDecodingSpec { this : EndpointHandlerBaseSpec =>

  val invitationJsonStr = """{
                           |	"label": "Artem1",
                           |	"serviceEndpoint": "http://vas.evernym.com/agency/msg",
                           |	"recipientKeys": ["CHGvy18MMJz3n3br5jRkAJbukwaLFo8vYW6D3MU9YQK5"],
                           |	"routingKeys": ["CHGvy18MMJz3n3br5jRkAJbukwaLFo8vYW6D3MU9YQK5", "ETLgZKeQEKxBW7gXA6FBn7nBwYhXFoogZLCCn5EeRSQV"],
                           |	"profileUrl": "https://pbs.twimg.com/profile_images/1022255393395929088/0eYH-Os_.jpg",
                           |	"@type": "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/invitation",
                           |	"@id": "b8eef641-1a56-4825-986a-7b4ed6c0d3d0"
                           |}""".stripMargin
  lazy val encodedInvitation = Base64Util.getBase64UrlEncoded(invitationJsonStr.getBytes)

  def testAriesInvitationDecoding(): Unit = {
    "when request is made to invitation URL" - {
      "should respond with correct invitation JSON" in {
        Get(s"/agency/msg/?c_i=$encodedInvitation")
          .withHeaders(`X-Forwarded-For`(RemoteAddress(InetAddress.getByName("127.0.0.1")))) ~> epRoutes ~> check {
          status shouldBe OK
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          val responseJson = new JSONObject(responseAs[String])
          responseJson.toMap shouldBe new JSONObject(invitationJsonStr).toMap
        }
      }
    }

    "when request is made to out-of-band invitation URL" - {
      "should respond with correct invitation JSON" in {
        Get(s"/agency/msg/?oob=$encodedInvitation")
          .withHeaders(`X-Forwarded-For`(RemoteAddress(InetAddress.getByName("127.0.0.1")))) ~> epRoutes ~> check {
          status shouldBe OK
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          val responseJson = new JSONObject(responseAs[String])
          responseJson.toMap shouldBe new JSONObject(invitationJsonStr).toMap
        }
      }
    }

    "when request is made to invitation URL with invalid base64 string" - {
      "should respond with correct invitation JSON" in {
        Get(s"/agency/msg/?oob=aaa")
          .withHeaders(`X-Forwarded-For`(RemoteAddress(InetAddress.getByName("127.0.0.1")))) ~> epRoutes ~> check {
          status shouldBe BadRequest
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          println(s"response: ${responseAs[String]}")
          val responseJson = new JSONObject(responseAs[String])
          responseJson.getString("statusCode") shouldBe "GNR-101"
        }
      }
    }

    "when request is made to out-of-band invitation URL with invalid base64 string" - {
      "should respond with correct invitation JSON" in {
        Get(s"/agency/msg/?oob=aaa")
          .withHeaders(`X-Forwarded-For`(RemoteAddress(InetAddress.getByName("127.0.0.1")))) ~> epRoutes ~> check {
          status shouldBe BadRequest
          header[`Content-Type`] shouldEqual Some(`Content-Type`(`application/json`))
          println(s"response: ${responseAs[String]}")
          val responseJson = new JSONObject(responseAs[String])
          responseJson.getString("statusCode") shouldBe "GNR-101"
        }
      }
    }
  }
}
