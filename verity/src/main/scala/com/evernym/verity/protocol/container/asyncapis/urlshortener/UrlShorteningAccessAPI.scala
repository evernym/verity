package com.evernym.verity.protocol.container.asyncapis.urlshortener

import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.BaseAsyncAccessImpl
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAccess
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo, UrlShorteningResponse}

import scala.util.Try

class UrlShorteningAccessAPI(implicit val asyncAPIContext: AsyncAPIContext)
  extends UrlShorteningAccess
    with BaseAsyncAccessImpl {

  override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit = {
    context.system.actorOf(DefaultURLShortener.props(asyncAPIContext.appConfig)).tell(UrlInfo(longUrl), senderActorRef)
  }
}
