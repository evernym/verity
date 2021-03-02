package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.engine.asyncapi.{AsyncOpRunner, UrlShorteningAccess}
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccessController
import com.evernym.verity.protocol.testkit.MockableUrlShorteningAccess
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.urlshortener.UrlShortened

import scala.util.Success

class UrlShortenerAccessControllerSpec extends BasicSpec {

  implicit val asyncRunner: AsyncOpRunner = null

  "Url Shortener access controller" - {
    "when given correct access rights" - {
      "should pass the access right checks" in {
        val controller = new UrlShorteningAccessController(
          Set(UrlShorteningAccess),
          new MockableUrlShorteningAccess
        )

        controller.shorten("hello") { x => x shouldBe Success(UrlShortened("http://short.url")) }
      }
    }

    "when given wrong access rights" - {
      "should fail the access right checks" in {
        val controller = new UrlShorteningAccessController(Set(), new MockableUrlShorteningAccess)

        controller.shorten("hello") { x => assert(x.isFailure)  }
      }
    }
  }
}
