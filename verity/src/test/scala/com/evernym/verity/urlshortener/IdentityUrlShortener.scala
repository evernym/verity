package com.evernym.verity.urlshortener

import akka.actor.ActorSystem
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants.URL_SHORTENER_PROVIDER_ID_IN_IDENTITY

import scala.concurrent.{ExecutionContext, Future}


class IdentityUrlShortener(val appConfig: AppConfig, ec: ExecutionContext) extends URLShortenerAPI {
  private implicit lazy val futureExecutionContext: ExecutionContext = ec

  override val providerId: String = URL_SHORTENER_PROVIDER_ID_IN_IDENTITY

  override def shortenURL(urlInfo: UrlInfo)(implicit actorSystem: ActorSystem): Future[String] = {
    Future(urlInfo.url)
  }
}
