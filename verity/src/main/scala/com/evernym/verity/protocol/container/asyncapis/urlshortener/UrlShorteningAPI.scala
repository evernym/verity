package com.evernym.verity.protocol.container.asyncapis.urlshortener

import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.BaseAsyncAccessImpl
import com.evernym.verity.protocol.engine.asyncapi.urlShorter.UrlShorteningAsyncOps
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo}

import scala.concurrent.ExecutionContext

class UrlShorteningAPI(executionContext: ExecutionContext)(implicit val asyncAPIContext: AsyncAPIContext)
  extends UrlShorteningAsyncOps
    with BaseAsyncAccessImpl {

  override def runShorten(longUrl: String): Unit =
    context
      .system
      .actorOf(DefaultURLShortener.props(asyncAPIContext.appConfig, executionContext))
      .tell(UrlInfo(longUrl), senderActorRef)
}
