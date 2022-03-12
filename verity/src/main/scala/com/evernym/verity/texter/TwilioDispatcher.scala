package com.evernym.verity.texter

import com.evernym.verity.config.AppConfig
import com.evernym.verity.util2.Exceptions.{InternalServerErrorException, SmsSendingFailedException}
import com.evernym.verity.util2.Status._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.http.common.ConfigSvc
import com.twilio.sdk.{TwilioRestClient, TwilioRestException}
import com.twilio.sdk.resource.factory.SmsFactory

import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._
import scala.concurrent.{Future}


class TwilioDispatcher(val appConfig: AppConfig)
  extends SMSServiceProvider
    with ConfigSvc {

  lazy val providerId = SMS_PROVIDER_ID_TWILIO
  lazy val from: String = appConfig.getStringReq(TWILIO_DEFAULT_NUMBER)
  lazy val token: String = appConfig.getStringReq(TWILIO_TOKEN)
  lazy val accountSid: String = appConfig.getStringReq(TWILIO_ACCOUNT_SID)

  lazy val client = new AtomicReference[TwilioRestClient](new TwilioRestClient(accountSid, token))

  lazy val messageFactory: SmsFactory = client.get.getAccount().getSmsFactory

  def sendMessage(smsInfo: SmsInfo): Future[SmsReqSent] = {
    try {
      val params = Map("Body" -> smsInfo.text, "To" -> smsInfo.to, "From" -> from)
      val smsInstance = messageFactory.create(params.asJava)
      Future.successful(SmsReqSent(smsInstance.getSid, providerId))
    } catch {
      case e: TwilioRestException =>
        Future.failed(new SmsSendingFailedException(Option(e.getMessage)))
      case e: Exception =>
        Future.failed(new InternalServerErrorException(UNHANDLED.statusCode, Option(e.getMessage)))
    }
  }

}
