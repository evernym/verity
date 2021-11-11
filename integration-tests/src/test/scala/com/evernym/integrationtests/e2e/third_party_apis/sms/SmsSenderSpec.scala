package com.evernym.integrationtests.e2e.third_party_apis.sms

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.texter._
import com.typesafe.scalalogging.Logger

import java.util.concurrent.TimeUnit
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration.{Duration, FiniteDuration}


class SmsSenderSpec
  extends TestKitBase
    with BasicSpec
    with ImplicitSender {

  "Default Sms Sender" - {
    "when tried to send sms" - {
      "based on the preferred-order configuration" - {
        "should be able to send it successfully" in {
          smsSender ! SmsInfo("4045943696", "test msg")
          expectMsgPF(duration) {
            case ss: SmsSent => println("smsSent: " + ss)
          }
        }
      }
    }
  }

  val logger: Logger = getLoggerByClass(getClass)

  val appConfig: AppConfig =
    new TestAppConfig(Option(
      ConfigFactory.parseString(
        """verity.services.sms-service.external-services {
              preferred-order = ["IB"]
              info-bip {
                endpoint {
                  host = ""
                  port = "443"
                  path-prefix = "sms/2/text/advanced"
                }
                access-token = ""
              }
          }
        """.stripMargin
      )
    ), clearValidators = true, baseAsFallback = false)

  lazy val smsSender: ActorRef = system.actorOf(Props(new DefaultSMSSender(appConfig, global)))
  lazy val duration: FiniteDuration = Duration.create(15, TimeUnit.SECONDS)

  override implicit val system: ActorSystem = ActorSystemVanilla("test", appConfig.config)
}
