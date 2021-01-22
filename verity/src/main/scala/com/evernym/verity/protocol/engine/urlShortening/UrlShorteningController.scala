package com.evernym.verity.protocol.engine.urlShortening

import com.evernym.verity.protocol.engine.external_api_access.{AccessRight, UrlShorteningAccess}
import scala.util.{Failure, Try}

class UrlShorteningController(accessRights: Set[AccessRight], urlShorteningImpl: UrlShorteningService)
  extends UrlShorteningService {

  def runIfAllowed[T](right: AccessRight, f: (Try[T] => Unit) => Unit, handler: Try[T] => Unit): Unit =
    if(accessRights(right)) f(handler)
    else handler(Failure(new IllegalArgumentException))

  override def shorten(inviteUrl: String)(handler: Try[InviteShortened] => Unit): Unit =
    runIfAllowed(UrlShorteningAccess, {urlShorteningImpl.shorten(inviteUrl)}, handler)

}
