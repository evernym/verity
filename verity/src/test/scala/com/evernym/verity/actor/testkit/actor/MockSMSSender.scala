package com.evernym.verity.actor.testkit.actor

import com.evernym.verity.util2.Exceptions.HandledErrorException
import com.evernym.verity.config.AppConfig
import com.evernym.verity.texter.{SMSSender, SmsInfo}
import com.evernym.verity.util2.HasExecutionContextProvider

import scala.concurrent.{ExecutionContext, Future}

trait MockSMSSenderExt extends SMSSender {
  var phoneTexts: Map[String, String]
}

class MockSMSSender(val appConfig: AppConfig, ec: ExecutionContext) extends MockSMSSenderExt with HasExecutionContextProvider {
  implicit val executionContext: ExecutionContext = ec
  var phoneTexts = Map.empty[String, String]

  override def sendMessage(smsInfo: SmsInfo): Future[Either[HandledErrorException, String]] = {
    phoneTexts += (smsInfo.to -> smsInfo.text)
    Future(Right("sms-sent"))
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ec
}
