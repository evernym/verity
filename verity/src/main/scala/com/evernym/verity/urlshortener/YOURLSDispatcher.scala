package com.evernym.verity.urlshortener

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{FormData, HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.URL_SHORTENING_FAILED
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.{YOURLS_API_PASSWORD, YOURLS_API_SIGNATURE, YOURLS_API_TIMEOUT_SECONDS, YOURLS_API_URL, YOURLS_API_USERNAME}
import com.evernym.verity.constants.Constants.URL_SHORTENER_PROVIDER_ID_YOURLS
import com.evernym.verity.http.common.ConfigSvc
import com.evernym.verity.util.OptionUtil
import com.evernym.verity.util.Util.{buildHandledError, logger}
import org.json.JSONObject

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class YOURLSSvc(val appConfig: AppConfig) extends YOURLSDispatcher

trait YOURLSDispatcher extends URLShortenerAPI with ConfigSvc {
  val providerId: String = URL_SHORTENER_PROVIDER_ID_YOURLS

  lazy val timeout: Duration = appConfig.getConfigIntOption(YOURLS_API_TIMEOUT_SECONDS).getOrElse(10).seconds

  lazy val apiUrl: String = appConfig.getConfigStringReq(YOURLS_API_URL)
  lazy val apiSignature: Option[String] = OptionUtil.blankFlattenOption(appConfig.getConfigStringOption(YOURLS_API_SIGNATURE))
  lazy val apiUsername: String = appConfig.getConfigStringReq(YOURLS_API_USERNAME)
  lazy val apiPassword: String = appConfig.getConfigStringReq(YOURLS_API_PASSWORD)
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
                         (implicit actorSystem: ActorSystem): Either[HandledErrorException, String] = {
    try {
      val fut: Future[Either[HandledErrorException, String]] = {
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
                Right(responseJson.getString("shorturl"))
              } else {
                logger.warn(s"Url shortener failed, wrong result: $responseJson")
                Left(buildHandledError(URL_SHORTENING_FAILED))
              }
            }
          } else {
            logger.warn(s"Url shortening failed, wrong status: ${response.status}")
            Future(Left(buildHandledError(URL_SHORTENING_FAILED)))
          }
        } recover {
          case e =>
            logger.warn(s"Url shortening failed: $e")
            Left(buildHandledError(URL_SHORTENING_FAILED))
        }
      }

      Await.result(fut, timeout)
    }
    catch {
      case e: Throwable =>
        logger.warn(s"Url shortening failed with exception: ${e.getMessage}", e)
        Left(buildHandledError(URL_SHORTENING_FAILED))
    }
  }
}
