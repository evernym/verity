package com.evernym.verity.protocol.container.asyncapis.urlshortener

import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.BaseAsyncAccessImpl
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.{UrlShorteningAccess, UrlShorteningAsyncOps}
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo, UrlShorteningResponse}

import scala.util.Try

class UrlShorteningAPI(implicit val asyncAPIContext: AsyncAPIContext)
  extends UrlShorteningAsyncOps
    with BaseAsyncAccessImpl {

  override def runShorten(longUrl: String): Unit =
    context
      .system
      .actorOf(DefaultURLShortener.props(asyncAPIContext.appConfig))
      .tell(UrlInfo(longUrl), senderActorRef)
}
