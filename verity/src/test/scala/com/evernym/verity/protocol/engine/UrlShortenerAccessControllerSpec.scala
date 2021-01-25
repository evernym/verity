package com.evernym.verity.protocol.engine

import com.evernym.verity.protocol.engine.external_api_access.UrlShorteningAccess
import com.evernym.verity.protocol.engine.urlShortening.{InviteShortened, UrlShorteningAccessController}
import com.evernym.verity.protocol.testkit.MockableUrlShorteningAccess
import com.evernym.verity.testkit.BasicSpec

import scala.util.{Failure, Success}

class UrlShortenerAccessControllerSpec extends BasicSpec {

  "Url Shortener access controller" - {
    "when given correct access rights" - {
      "should pass the access right checks" in {
        val controller = new UrlShorteningAccessController(
          Set(UrlShorteningAccess),
          new MockableUrlShorteningAccess
        )

        controller.shorten("hello") { x => x shouldBe Success(InviteShortened("hello", "http://short.url")) }
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
