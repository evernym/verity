package com.evernym.verity.protocol.engine

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccessAdapter
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.urlshortener.UrlShortened

import scala.util.{Failure, Success}

class UrlShortenerAccessAdapterSpec extends BasicSpec with MockAsyncOpRunner {
  implicit def asyncAPIContext: AsyncAPIContext = AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)



  "Url Shortener access controller" - {
    "when shortening completed successfully" - {
      "should be success result passed to handler" in {
        val controller = new UrlShorteningAccessAdapter(
          null
        ){
          override protected def runShorten(longUrl: String): Unit = Success(UrlShortened(longUrl))
        }

        controller.shorten("hello") { x => x shouldBe Success(UrlShortened("http://short.url")) }
      }
    }

    "when shortening fails" - {
      "should be failure passed to handler" in {
        val controller = new UrlShorteningAccessAdapter(null){
          override protected def runShorten(longUrl: String): Unit = Failure(new Exception)
        }
        controller.shorten("hello") { x => x shouldBe Failure(new Exception)  }
      }
    }
  }
}
