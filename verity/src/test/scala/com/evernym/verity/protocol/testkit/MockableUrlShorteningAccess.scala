package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.urlshortener.{UrlShortened, UrlShorteningResponse}

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