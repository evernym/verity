package com.evernym.verity.http.route_handlers.configured

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Directives.{complete, handleExceptions, logRequestResult, pathPrefix, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions.NotFoundErrorException
import com.evernym.verity.Status._
import com.evernym.verity.actor.url_mapper.{AddUrl, GetActualUrl}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.common.HttpRouteBase
import com.evernym.verity.http.route_handlers.PlatformServiceProvider
import com.evernym.verity.urlmapper.UrlAdded
import com.evernym.verity.util.Util._

import scala.concurrent.Future

/**
 * an url mapping (short url to long url) service
 * right now hosted on CAS (but it can be hosted separately as well)
 */
trait UrlMapperEndpointHandler
  extends HttpRouteBase
    with PlatformServiceProvider {

  protected def createUrlMapping(urlJson: String): Future[Any] = {
    val agencyPayloadData = getMapWithStringValueFromJsonString(urlJson)
    implicit val hashed: String = getRequiredField(HASHED_URL, agencyPayloadData)
    val url = getRequiredField(URL, agencyPayloadData)
    val respFut = platform.urlStore ? AddUrl(url)
    respFut map {
      case _: UrlAdded =>
        val urlTemplate = platform.agentActorContext.appConfig.getConfigStringReq(CONNECT_ME_MAPPED_URL_TEMPLATE)
        val newUrl = replaceVariables(urlTemplate, Map(TOKEN -> hashed))
        Url(newUrl)
      case e => e
    }
  }

  protected def getMappedUrl(implicit hashed: String): Future[Any] = {
    val respFut = platform.urlStore ? GetActualUrl
    respFut map {
      case Some(url: String) => Url(url)
      case None => new NotFoundErrorException(RESOURCE_NOT_FOUND.statusCode, Option("no url found for given token: " + hashed))
      case e => e
    }
  }

  protected def urlMapperResponseHandler: PartialFunction[Any, ToResponseMarshallable] = {
    case u: Url => handleExpectedResponse(u)
    case e      => handleUnexpectedResponse(e)
  }

  protected val urlMapperRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("consumer-agency-service") {
        pathPrefix("agency" / "url-mapper") {
          (post & entity(as[String])) { msg =>
            complete {
              createUrlMapping(msg).map[ToResponseMarshallable] {
                urlMapperResponseHandler
              }
            }
          } ~
            pathPrefix(Segment) { implicit hashed =>
              (get & pathEnd) {
                complete {
                  getMappedUrl.map[ToResponseMarshallable] {
                    urlMapperResponseHandler
                  }
                }
              }
            }
        }
      }
    }
}

case class Url(url: String)