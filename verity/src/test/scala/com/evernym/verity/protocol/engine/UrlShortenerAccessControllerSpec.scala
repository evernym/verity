package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.engine.external_api_access.UrlShorteningAccess
import com.evernym.verity.protocol.engine.urlShortening.UrlShorteningAccessController
import com.evernym.verity.protocol.testkit.MockableUrlShorteningAccess
import com.evernym.verity.testkit.BasicSpec

import scala.util.{Success, Failure, Try}

class UrlShortenerAccessControllerSpec extends BasicSpec {

  "Url Shortener access controller" - {
    "when given correct access rights" - {
      "should pass the access right checks" in {
        val controller = new UrlShorteningAccessController(
          Set(UrlShorteningAccess),
          new MockableUrlShorteningAccess
        )

        controller.shorten("hello") { x => x shouldBe Success }
      }
    }

    "when given wrong access rights" - {
      "should fail the access right checks" in {
        val controller = new UrlShorteningAccessController(Set(), new MockableUrlShorteningAccess)

        controller.shorten("hello") { x => x shouldBe Failure }
      }
    }
  }
}
