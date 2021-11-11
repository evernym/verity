package com.evernym.verity.texter

import com.evernym.verity.config.AppConfig
import com.evernym.verity.util2.Exceptions.{HandledErrorException, InternalServerErrorException, SmsSendingFailedException}
import com.evernym.verity.util2.Status._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.http.common.ConfigSvc
import com.twilio.sdk.{TwilioRestClient, TwilioRestException}
import com.twilio.sdk.resource.factory.SmsFactory

import scala.collection.JavaConverters._
import scala.concurrent.{Future, SyncVar}


class TwilioDispatcher(val appConfig: AppConfig)
  extends SMSServiceProvider
    with ConfigSvc {

  lazy val providerId = SMS_PROVIDER_ID_TWILIO
  lazy val from: String = appConfig.getStringReq(TWILIO_DEFAULT_NUMBER)
  lazy val token: String = appConfig.getStringReq(TWILIO_TOKEN)
  lazy val accountSid: String = appConfig.getStringReq(TWILIO_ACCOUNT_SID)

  lazy val client = {
    val trc = new SyncVar[TwilioRestClient]
    trc.put(new TwilioRestClient(accountSid, token))
    trc
  }

  lazy val messageFactory: SmsFactory = client.get.getAccount().getSmsFactory

  def sendMessage(smsInfo: SmsInfo): Future[Either[HandledErrorException, SmsSent]] = {
    try {
      val params = Map("Body" -> smsInfo.text, "To" -> smsInfo.to, "From" -> from)
      val smsInstance = messageFactory.create(params.asJava)
      Future.successful(Right(SmsSent(smsInstance.getSid, providerId)))
    } catch {
      case e: TwilioRestException =>
        Future.successful(Left(new SmsSendingFailedException(Option(e.getMessage))))
      case e: Exception =>
        Future.successful(Left(new InternalServerErrorException(UNHANDLED.statusCode, Option(e.getMessage))))
    }
  }
}
