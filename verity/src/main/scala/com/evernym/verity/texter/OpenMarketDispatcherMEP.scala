package com.evernym.verity.texter

import java.net.HttpURLConnection._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.util.Util._
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.http.common.ConfigSvc
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider

import javax.ws.rs.client.{Client, ClientBuilder, Entity}
import javax.ws.rs.core.MediaType
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature

import scala.collection.JavaConverters._

case class Session(TextMessage: String)
case class Variables(session: Session)
case class EndUser(phoneNumber: String)
case class InvokeService(endUser: EndUser, variables: Variables)


trait OpenMarketMEPAPI extends SMSServiceProvider with ConfigSvc {

  private val logger = getLoggerByName("OpenMarketMEPAPI")

  lazy val providerId = SMS_PROVIDER_ID_OPEN_MARKET

  lazy val userName: String = appConfig.getStringReq(OPEN_MARKET_USER_NAME)
  lazy val password: String = appConfig.getStringReq(OPEN_MARKET_PASSWORD)
  lazy val webApiHost: String = appConfig.getStringReq(OPEN_MARKET_ENDPOINT_HOST)
  lazy val webApiUrlPrefix: String = appConfig.getStringReq(OPEN_MARKET_ENDPOINT_PATH_PREFIX)
  lazy val serviceId: String = appConfig.getStringReq(OPEN_MARKET_SERVICE_ID)
  lazy val webApiUrl: String = "https://" + webApiHost
  lazy val baseResourcePrefix: String = s"$webApiUrlPrefix"
  lazy val sendMsgResource: String = s"$webApiUrl/$baseResourcePrefix/$serviceId"

  lazy val client: Client = {
    ClientBuilder.newBuilder
      .register(classOf[ObjectMapper])
      .register(classOf[JacksonJaxbJsonProvider]).build
      .register(HttpAuthenticationFeature.basic(userName, password), 1)
  }

  def sendMessage(smsInfo: SmsInfo): Either[HandledErrorException, SmsSent] = {
    val message = InvokeService(EndUser(smsInfo.to), Variables(Session(smsInfo.text)))
    val jsonEntity = DefaultMsgCodec.toJson(message)
    val target = client.target(sendMsgResource)
    val result = target.request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(jsonEntity,
        MediaType.APPLICATION_JSON_TYPE))
    if (result.getStatus != HTTP_ACCEPTED)  {
      logger.error("error while sending sms with open market: " + result.getStatusInfo.toString)
      Left(buildHandledError(result.getStatus.toString, Option(result.getStatusInfo.getStatusCode.toString),
        Option(result.getStatusInfo.getReasonPhrase)))
    } else {
      val msgId =
        result.getHeaders.asScala.find(_._1 == "X-Request-Id").map { h =>
          h._2.asScala.head.toString
        }
      Right(SmsSent(msgId.getOrElse(EMPTY_STRING), providerId))
    }
  }

  override def getNormalizedPhoneNumber(ph: String): String = {
    val baseNormalizedPhoneNumber = super.getNormalizedPhoneNumber(ph)
    baseNormalizedPhoneNumber.replaceFirst("\\+", "00")
  }
}
