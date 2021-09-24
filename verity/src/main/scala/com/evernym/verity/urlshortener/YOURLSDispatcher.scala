package com.evernym.verity.urlshortener

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{FormData, HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.{YOURLS_API_PASSWORD, YOURLS_API_SIGNATURE, YOURLS_API_URL, YOURLS_API_USERNAME}
import com.evernym.verity.constants.Constants.URL_SHORTENER_PROVIDER_ID_YOURLS
import com.evernym.verity.http.common.ConfigSvc
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.util.OptionUtil
import com.evernym.verity.util.Util.buildHandledError
import com.evernym.verity.util2.Status.URL_SHORTENING_FAILED
import org.json.JSONObject

import scala.concurrent.{ExecutionContext, Future}


class YOURLSSvc(val appConfig: AppConfig, executionContext: ExecutionContext) extends URLShortenerAPI with ConfigSvc {
  private implicit lazy val futureExecutionContext: ExecutionContext = executionContext

  private val logger = getLoggerByName("YOURLSDispatcher")

  val providerId: String = URL_SHORTENER_PROVIDER_ID_YOURLS

  lazy val apiUrl: String = appConfig.getStringReq(YOURLS_API_URL)
  lazy val apiSignature: Option[String] = OptionUtil.blankFlattenOption(appConfig.getStringOption(YOURLS_API_SIGNATURE))
  lazy val apiUsername: String = appConfig.getStringReq(YOURLS_API_USERNAME)
  lazy val apiPassword: String = appConfig.getStringReq(YOURLS_API_PASSWORD)
  lazy val formData: Map[String, String] = Map(
    "action" -> "shorturl",
    "format" -> "json"
  ) ++ authorisation()

  def authorisation(): Map[String, String] = {
    apiSignature match {
      case Some(value) => Map("signature" -> value)
      case None =>
        Map(
          "username" -> apiUsername,
          "password" -> apiPassword
        )
    }
  }

  def httpClient(request: HttpRequest)
                (implicit actorSystem: ActorSystem): Future[HttpResponse] = Http().singleRequest(request)

  override def shortenURL(urlInfo: UrlInfo)
                         (implicit actorSystem: ActorSystem): Future[String] = {
    httpClient(
      HttpRequest(
        method = HttpMethods.POST,
        uri = apiUrl,
        entity = FormData(formData + ("url" -> urlInfo.url)).toEntity,
      )
    ) flatMap { response =>
      if (response.status == OK) {
        Unmarshal(response.entity).to[String] map {responseContent =>
          val responseJson = new JSONObject(responseContent)
          if (responseJson.getString("status") == "success") {
            responseJson.getString("shorturl")
          } else {
            throw new RuntimeException(s"Received invalid response: $responseJson")
          }
        }
      } else {
        throw new RuntimeException(s"Received HTTP status: ${response.status}")
      }
    } recover {
      case e: Throwable =>
        logger.warn(s"Url shortening failed: ${e.getMessage}")
        throw buildHandledError(URL_SHORTENING_FAILED)
    }
  }
}
