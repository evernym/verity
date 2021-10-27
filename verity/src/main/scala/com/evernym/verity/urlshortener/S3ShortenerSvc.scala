package com.evernym.verity.urlshortener

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, ContentTypes, Uri}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.{S3_SHORTENER_BUCKET_NAME, S3_SHORTENER_ID_LENGTH, S3_SHORTENER_RETRY_COUNT, S3_SHORTENER_URL_PREFIX}
import com.evernym.verity.constants.Constants.URL_SHORTENER_PROVIDER_ID_S3_SHORTENER
import com.evernym.verity.http.common.ConfigSvc
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util.Util.buildHandledError
import com.evernym.verity.util2.Status.URL_SHORTENING_FAILED
import org.apache.commons.lang3.RandomStringUtils

import scala.concurrent.{ExecutionContext, Future}

class S3ShortenerSvc(val appConfig: AppConfig, implicit val futureExecutionContext: ExecutionContext) extends URLShortenerAPI with ConfigSvc {
  private val logger = getLoggerByName("S3ShortenerSvc")

  override val providerId: String = URL_SHORTENER_PROVIDER_ID_S3_SHORTENER

  val DEFAULT_ID_LENGTH: Int = 8
  val DEFAULT_RETRY_COUNT: Int = 3

  lazy val urlPrefix: String = appConfig.getStringReq(S3_SHORTENER_URL_PREFIX)
  lazy val bucketName: String = appConfig.getStringReq(S3_SHORTENER_BUCKET_NAME)
  lazy val idLength: Int = appConfig.getIntOption(S3_SHORTENER_ID_LENGTH)
    .getOrElse(DEFAULT_ID_LENGTH)
  lazy val retryCount: Int = appConfig.getIntOption(S3_SHORTENER_RETRY_COUNT)
    .getOrElse(DEFAULT_RETRY_COUNT)

  def genShortId(): String = RandomStringUtils.randomAlphanumeric(idLength)

  def decodeInviteURL(inviteURL: String): Array[Byte] = {
    val query = Uri(inviteURL).query()
    query.get("oob").orElse(query.get("c_i")) match {
      case Some(base64text) =>
        Base64Util.getBase64UrlDecoded(base64text)
      case None =>
        throw new RuntimeException(s"Unable to decode invite URL: $inviteURL")
    }

  }

  def getStorageAPI(implicit as: ActorSystem): StorageAPI = StorageAPI.loadFromConfig(appConfig, futureExecutionContext)

  override def shortenURL(urlInfo: UrlInfo)
                         (implicit actorSystem: ActorSystem): Future[String] = {
    val storageAPI: StorageAPI = getStorageAPI
    val utf8Data: Array[Byte] = decodeInviteURL(urlInfo.url)

    // store into S3 bucket
    storeWithRetry(storageAPI, utf8Data, retryCount) map { id =>
      urlPrefix + id
    }
  }

  def storeWithRetry(storageAPI: StorageAPI, data: Array[Byte], retryCount: Int): Future[String] = {
    // generate different id every time.
    val id = genShortId()

    storageAPI.put(bucketName, id, data, ContentTypes.`application/json`) map { _ =>
      id
    } recoverWith {
      case e: Throwable =>
        logger.warn(s"Storing short url data failed: ${e}")
        if (retryCount > 0)
          storeWithRetry(storageAPI, data, retryCount - 1)
        else
          throw buildHandledError(URL_SHORTENING_FAILED)
    }
  }
}
