package com.evernym.verity.protocol.testkit

import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.urlshortener.{UrlShortened, UrlShorteningResponse}

import scala.util.{Failure, Success, Try}

object MockableUrlShorteningAccess {
  def apply(): UrlShorteningAccess = defaultUrlShorteningSuccess
  def shortened: UrlShorteningAccess = defaultUrlShorteningSuccess
  def shorteningFailed: UrlShorteningAccess = defaultUrlShorteningFailure

  def defaultUrlShorteningFailure: UrlShorteningAccess = new UrlShorteningAccess {
    override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit =
      handler(Failure(new Exception("because")))
  }

  def defaultUrlShorteningSuccess: UrlShorteningAccess = new UrlShorteningAccess {
    override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit =
      handler(Success(UrlShortened("http://short.url")))
  }
}