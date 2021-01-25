package com.evernym.integrationtests.e2e.third_party_apis.sms

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import com.evernym.verity.config.{AppConfig, AppConfigWrapper}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.texter._
import com.typesafe.scalalogging.Logger

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, FiniteDuration}

class SmsSenderSpec extends TestKit(ActorSystem("test")) with BasicSpec with ImplicitSender {
  val logger: Logger = getLoggerByClass(getClass)

  val appConfig: AppConfig = AppConfigWrapper

  val smsSender: ActorRef = system.actorOf(Props(new DefaultSMSSender(appConfig)))
  val duration: FiniteDuration = Duration.create(15, TimeUnit.SECONDS)

  "Default Sms Sender" - {
    "when tried to send sms" - {
      "should be able to send it successfully" in {
        smsSender ! SmsInfo("4045943696", "test msg")
        expectMsgPF(duration) {
          case ss: SmsSent =>

            logger.info("smsSent: " + ss)
        }
      }
    }
  }
}
