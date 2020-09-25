package com.evernym.verity.http.consumer

import akka.http.scaladsl.model.StatusCodes._
import com.evernym.verity.constants.Constants.{HASHED_URL, URL}
import com.evernym.verity.util.Util.getJsonStringFromMap

trait UrlMappingSpec { this : ConsumerEndpointHandlerSpec =>

  def testUrlMapping(): Unit = {
    "when sent add url mapping api call" - {
      "should respond with success" in {
        val jsonData = getJsonStringFromMap(Map(URL -> actualLongUrl, HASHED_URL -> shortenedUrl))
        Post(s"/agency/url-mapper", jsonData) ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] shouldBe s"""{"url":"https://connectme.app.link?t=$shortenedUrl"}"""
        }
      }
    }

    "when sent get url mapping api call" - {
      "should respond with correct mapping details" in {
        Get(s"/agency/url-mapper/$shortenedUrl") ~> epRoutes ~> check {
          status shouldBe OK
          responseAs[String] shouldBe s"""{"url":"$actualLongUrl"}"""
        }
      }
    }
  }
}
