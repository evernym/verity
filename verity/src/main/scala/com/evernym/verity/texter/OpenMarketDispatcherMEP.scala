package com.evernym.verity.texter

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.{AppConfig, ConfigSvc}
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.Constants._
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider
import jakarta.ws.rs.client.{Client, ClientBuilder, Entity}
import jakarta.ws.rs.core.MediaType
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature

import java.net.HttpURLConnection._
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

case class Session(TextMessage: String)
case class Variables(session: Session)
case class EndUser(phoneNumber: String)
case class InvokeService(endUser: EndUser, variables: Variables)


class OpenMarketDispatcherMEP (val appConfig: AppConfig)
  extends SMSServiceProvider
    with ConfigSvc {

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
    ClientBuilder
      .newBuilder
      .register(classOf[ObjectMapper])
      .register(classOf[JacksonJsonProvider])
      .register(HttpAuthenticationFeature.basic(userName, password), 1)
      .build
  }

  def sendMessage(smsInfo: SmsInfo): Future[SmsReqSent] = {
    val message = InvokeService(EndUser(smsInfo.to), Variables(Session(smsInfo.text)))
    val jsonEntity = DefaultMsgCodec.toJson(message)
    val target = client.target(sendMsgResource)
    val result = target.request(MediaType.APPLICATION_JSON_TYPE)
      .post(Entity.entity(jsonEntity,
        MediaType.APPLICATION_JSON_TYPE))
    if (result.getStatus != HTTP_ACCEPTED)  {
      Future.failed(new RuntimeException("unexpected http status: " + result.getStatusInfo.toString))
    } else {
      val msgId =
        result.getHeaders.asScala.find(_._1 == "X-Request-Id").map { h =>
          h._2.asScala.head.toString
        }
      Future.successful(SmsReqSent(msgId.getOrElse(EMPTY_STRING), providerId))
    }
  }

  override def getNormalizedPhoneNumber(ph: String): String = {
    val baseNormalizedPhoneNumber = super.getNormalizedPhoneNumber(ph)
    baseNormalizedPhoneNumber.replaceFirst("\\+", "00")
  }
}
