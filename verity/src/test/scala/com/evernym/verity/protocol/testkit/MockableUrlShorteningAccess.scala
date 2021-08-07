package com.evernym.verity.protocol.testkit

import akka.actor.ActorRef
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.urlshortener.UrlShorteningAPI
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.urlshortener.{UrlShortened, UrlShorteningResponse}
import com.evernym.verity.util.TestExecutionContextProvider

import scala.util.{Failure, Success, Try}

object MockableUrlShorteningAccess {
  def apply(): MockableUrlShorteningAccess = new MockableUrlShorteningAccess
  def shortened = new MockableUrlShorteningAccess(defaultUrlShorteningSuccess)
  def shorteningFailed = new MockableUrlShorteningAccess(defaultUrlShorteningFailure)

  def defaultUrlShorteningFailure: UrlShorteningAccess = new UrlShorteningAccess {
    override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit =
      handler(Failure(new Exception("because")))
  }

  def defaultUrlShorteningSuccess: UrlShorteningAccess = new UrlShorteningAccess {
    override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit =
      handler(Success(UrlShortened("http://short.url")))
  }
}

class MockableUrlShorteningAccess(mockShortening: UrlShorteningAccess = MockableUrlShorteningAccess.defaultUrlShorteningSuccess)
  extends UrlShorteningAccess {

  override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit =
    mockShortening.shorten(longUrl)(handler)
}

object MockableUrlShorteningAPI {
  implicit def asyncAPIContext: AsyncAPIContext = AsyncAPIContext(new TestAppConfig, ActorRef.noSender, null)
  def failed: UrlShorteningAPI = new UrlShorteningAPI(TestExecutionContextProvider.ecp.futureExecutionContext) {
    override def runShorten(longUrl: String): Unit = Failure(throw new Exception)
  }

  def success: UrlShorteningAPI = new UrlShorteningAPI(TestExecutionContextProvider.ecp.futureExecutionContext) {
    override def runShorten(longUrl: String): Unit = Success(UrlShortened(longUrl))
  }
}
