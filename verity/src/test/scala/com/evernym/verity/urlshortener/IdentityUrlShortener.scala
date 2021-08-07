package com.evernym.verity.urlshortener

import akka.actor.ActorSystem
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants.URL_SHORTENER_PROVIDER_ID_IN_IDENTITY
import com.evernym.verity.util2.Exceptions

import scala.concurrent.ExecutionContext


class IdentityUrlShortener(val appConfig: AppConfig, ec: ExecutionContext)
  extends URLShortenerAPI {

  override val providerId: String = URL_SHORTENER_PROVIDER_ID_IN_IDENTITY

  override def shortenURL(urlInfo: UrlInfo)(implicit actorSystem: ActorSystem):
  Either[Exceptions.HandledErrorException, String] = {
    Right(urlInfo.url)
  }
}
