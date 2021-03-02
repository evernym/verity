package com.evernym.verity.protocol.engine.asyncapi.urlShorter

import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, UrlShorteningAccess => AccessForUrlShortening}
import com.evernym.verity.urlshortener.UrlShorteningResponse

import scala.util.{Failure, Try}

class UrlShorteningAccessController(accessRights: Set[AccessRight],
                                    urlShorteningImpl: UrlShorteningAccess)
  extends UrlShorteningAccess {

  def runIfAllowed[T](right: AccessRight, f: (Try[T] => Unit) => Unit, handler: Try[T] => Unit): Unit =
    if(accessRights(right)) f(handler)
    else handler(Failure(new IllegalArgumentException))

  override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit =
    runIfAllowed(AccessForUrlShortening, {urlShorteningImpl.shorten(longUrl)}, handler)

}
