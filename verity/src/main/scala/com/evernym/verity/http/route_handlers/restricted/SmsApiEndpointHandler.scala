package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractClientIP, extractRequest, handleExceptions, logRequestResult, path, pathPrefix, post, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.resourceusagethrottling.RESOURCE_TYPE_ENDPOINT
import com.evernym.verity.constants.Constants.{PHONE_NO, TEXT}
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.http.common.CustomExceptionHandler.{exceptionHandler, handleUnexpectedResponse}
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.texter.SmsInfo
import com.evernym.verity.util.Util.{getMapWithStringValueFromJsonString, getRequiredField}

import scala.concurrent.Future

/**
 * not sure if we are using this anymore
 * it was created to be used by enterprise agent to hit this api (mostly hosted on consumer agent)
 * which will then send sms
 */
trait SmsApiEndpointHandler extends ResourceUsageCommon { this: HttpRouteWithPlatform =>

  protected def sendSms(jsonStr: String): Future[Any] = {
    val agencyPayloadData = getMapWithStringValueFromJsonString(jsonStr)
    val phoneNo = getRequiredField(PHONE_NO, agencyPayloadData)
    val text = getRequiredField(TEXT, agencyPayloadData)
    platform.agentActorContext.smsSvc.sendMessage(SmsInfo(phoneNo, text))
  }

  protected val smsSvcRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency") {
          path("sms") {
            (post & pathEnd & entity(as[String])) { msg =>
              extractRequest { implicit req =>
                extractClientIP { implicit remoteAddress =>
                  //TODO: this ip based check is temporarily until we have some better way to control it
                  checkIfSmsServiceApiCalledFromAllowedIPAddresses(clientIpAddress)
                  addUserResourceUsage(RESOURCE_TYPE_ENDPOINT,
                    "POST_agency_sms", Option(clientIpAddress), None)
                  complete {
                    sendSms(msg).map[ToResponseMarshallable] {
                      case Right(_: String) => OK
                      case e                => handleUnexpectedResponse(e)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
}
