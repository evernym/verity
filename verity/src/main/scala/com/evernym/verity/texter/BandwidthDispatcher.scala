package com.evernym.verity.texter

import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.config.{AppConfig, ConfigSvc}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.util2.Exceptions.{InternalServerErrorException, SmsSendingFailedException}
import com.evernym.verity.util2.Status._
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import jakarta.ws.rs.client.{Client, ClientBuilder, Entity}
import jakarta.ws.rs.core.MediaType
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature

import java.net.HttpURLConnection._
import scala.concurrent.Future
import scala.jdk.CollectionConverters._


class BandwidthDispatcher(val appConfig: AppConfig)
  extends SMSServiceProvider
    with ConfigSvc {

  lazy val providerId = SMS_PROVIDER_ID_BANDWIDTH
  lazy val from: String = appConfig.getStringReq(BANDWIDTH_DEFAULT_NUMBER)

  lazy val userId: String = appConfig.getStringReq(BANDWIDTH_USER_ID)
  lazy val token: String = appConfig.getStringReq(BANDWIDTH_TOKEN)
  lazy val secret: String = appConfig.getStringReq(BANDWIDTH_SECRET)
  lazy val webApiHost: String = appConfig.getStringReq(BANDWIDTH_ENDPOINT_HOST)
  lazy val webApiUrlPrefix: String = appConfig.getStringReq(BANDWIDTH_ENDPOINT_PATH_PREFIX)
  lazy val webApiUrl: String = "https://" + webApiHost
  lazy val baseResourcePrefix: String = s"$webApiUrlPrefix/$userId"
  lazy val sendMsgResource: String = s"$webApiUrl/$baseResourcePrefix/messages"

  lazy val client: Client = {
    ClientBuilder.newBuilder
      .register(classOf[ObjectMapper])
      .register(classOf[JacksonJsonProvider])
      .register(HttpAuthenticationFeature.basic(token, secret), 1)
      .build
  }

  /**
    * Remove carriage return and newline characters as BandWidth doesn't handle them.
    * Also replaces any \ characters with SPACE, double quotes with single quote
    */
  def removeSpecialCharacters(smsMsg: String): String = {
    smsMsg.replace(CARRIAGE_RETURN+NEW_LINE, SPACE).replace(BACKSLASH, SPACE).
      replace(NEW_LINE, SPACE).replace(CARRIAGE_RETURN, SPACE).replace(TAB, SPACE).replace(BACKSPACE, SPACE).
      replace(FORM_FEED, SPACE).replace(NULL_CHARACTER, SPACE).replaceAll(DOUBLE_QUOTE, DOUBLE_QUOTE_FOR_BANDWIDTH)
  }

  def sendMessage(smsInfo: SmsInfo): Future[SmsReqSent] = {
    val message = removeSpecialCharacters(smsInfo.text)
    val jsonEntity = """{"receiptRequested":"""" + "all" + """","from":"""" + from +
      """","to":"""" + smsInfo.to + """","text" :"""" + message + """"}"""
    val target = client.target(sendMsgResource)
    try {
      val result = target.request(MediaType.APPLICATION_JSON_TYPE)
        .post(Entity.entity(jsonEntity,
          MediaType.APPLICATION_JSON_TYPE))
      result.getStatus match {
        case HTTP_CREATED =>
          val msgId =
            result.getHeaders.asScala.find(_._1 == "Location").map { h =>
              h._2.asScala.head.toString.replaceAll(target.getUri.toString + FORWARD_SLASH, EMPTY_STRING)
            }
          Future.successful(SmsReqSent(msgId.getOrElse(EMPTY_STRING), providerId))
        case HTTP_UNAUTHORIZED =>
          Future.failed(new InternalServerErrorException(UNHANDLED.statusCode, Option(result.getStatusInfo.getReasonPhrase)))
        case _ =>
          Future.failed(new SmsSendingFailedException(Option(result.getStatusInfo.getReasonPhrase)))
      }
    } catch {
      case e: Exception =>
        Future.failed(new InternalServerErrorException(UNHANDLED.statusCode, Option(e.getMessage)))
    }
  }

}
