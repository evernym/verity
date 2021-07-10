package com.evernym.verity.actor.testkit.actor

import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.config.AppConfig
import com.evernym.verity.texter.{SMSSender, SmsInfo}

import scala.concurrent.Future

trait MockSMSSenderExt extends SMSSender {
  var phoneTexts: Map[String, String]
}

class MockSMSSender(val appConfig: AppConfig) extends MockSMSSenderExt {
  var phoneTexts = Map.empty[String, String]
  import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext

  override def sendMessage(smsInfo: SmsInfo): Future[Either[HandledErrorException, String]] = {
    phoneTexts += (smsInfo.to -> smsInfo.text)
    Future(Right("sms-sent"))
  }
}
