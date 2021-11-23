package com.evernym.verity.protocol.engine.asyncapi.urlShorter

import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.BaseAsyncAccessImpl
import com.evernym.verity.protocol.engine.asyncapi.{AsyncOpRunner, BaseAccessController}
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo, UrlShorteningResponse}

import scala.concurrent.ExecutionContext
import scala.util.Try

class UrlShorteningAccessAdapter(executionContext: ExecutionContext)
                                (implicit val asyncOpRunner: AsyncOpRunner,
                                    implicit val asyncAPIContext: AsyncAPIContext)
  extends UrlShorteningAccess
    with BaseAccessController
    with BaseAsyncAccessImpl {

  override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit =
    withAsyncOpRunner({runShorten(longUrl)}, handler)

  protected def runShorten(longUrl: String): Unit =
    context
      .system
      .actorOf(DefaultURLShortener.props(asyncAPIContext.appConfig, executionContext))
      .tell(UrlInfo(longUrl), senderActorRef)
}
