package com.evernym.verity.protocol.container.asyncapis.urlshortener

import akka.actor.{ActorContext, ActorRef}
import akka.util.Timeout
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.BaseAsyncAccessImpl
import com.evernym.verity.protocol.engine.asyncService.AsyncOpRunner
import com.evernym.verity.protocol.engine.asyncService.urlShorter.UrlShorteningAccess
import com.evernym.verity.urlshortener.{DefaultURLShortener, UrlInfo, UrlShorteningResponse}

import scala.util.Try

class UrlShorteningAccessAPI(context: ActorContext, appConfig: AppConfig)
                            (implicit val asyncAPIContext: AsyncAPIContext)
  extends UrlShorteningAccess
    with BaseAsyncAccessImpl {

  implicit val timeout: Timeout = asyncAPIContext.timeout
  implicit val asyncOpRunner: AsyncOpRunner = asyncAPIContext.asyncOpRunner
  implicit val senderActorRef: ActorRef = asyncAPIContext.senderActorRef

  override def shorten(longUrl: String)(handler: Try[UrlShorteningResponse] => Unit): Unit = {
    withAsyncOpRunner(
      { context.system.actorOf(DefaultURLShortener.props(appConfig)).tell(UrlInfo(longUrl), senderActorRef)},
      handler
    )
  }
}
