package com.evernym.verity.protocol.engine

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.asyncapi.UrlShorteningAccess
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccessController
import com.evernym.verity.protocol.testkit.MockableUrlShorteningAPI
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.urlshortener.UrlShortened

import scala.util.Success

class UrlShortenerAccessControllerSpec extends BasicSpec with MockAsyncOpRunner {
  implicit def asyncAPIContext: AsyncAPIContext = AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)

  "Url Shortener access controller" - {
    "when given correct access rights" - {
      "should pass the access right checks" in {
        val controller = new UrlShorteningAccessController(
          Set(UrlShorteningAccess),
          MockableUrlShorteningAPI.success
        )

        controller.shorten("hello") { x => x shouldBe Success(UrlShortened("http://short.url")) }
      }
    }

    "when given wrong access rights" - {
      "should fail the access right checks" in {
        val controller = new UrlShorteningAccessController(Set(), MockableUrlShorteningAPI.failed)
        controller.shorten("hello") { x => x.failed.get.isInstanceOf[IllegalArgumentException]  }
      }
    }
  }
}
