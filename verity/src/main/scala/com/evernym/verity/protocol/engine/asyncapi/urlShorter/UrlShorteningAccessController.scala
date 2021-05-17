package com.evernym.verity.protocol.engine.asyncapi.urlShorter

import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AsyncOpRunner, BaseAccessController, UrlShorteningAccess => AccessForUrlShortening}
import com.evernym.verity.urlshortener.UrlShorteningResponse

import scala.util.Try

class UrlShorteningAccessController(val accessRights: Set[AccessRight],
                                    urlShorteningExecutor: UrlShorteningAsyncOps)
                                   (implicit val asyncOpRunner: AsyncOpRunner)
  extends UrlShorteningAccess
    with BaseAccessController {

  override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit =
    runIfAllowed(AccessForUrlShortening, {urlShorteningExecutor.runShorten(longUrl)}, handler)

}
