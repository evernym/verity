//package com.evernym.integrationtests.e2e.third_party_apis.sms
//
//import java.util.concurrent.TimeUnit
//
//import akka.actor.{ActorRef, ActorSystem, Props}
//import akka.testkit.{ImplicitSender, TestKit}
//import com.evernym.verity.config.{AppConfig, AppConfigWrapper}
//import com.evernym.verity.texter._
//
//import scala.concurrent.duration.{Duration, FiniteDuration}
//
//// Note: This is written to test few scenarios against "real services (Bandwidth, Twilio, OpenMarket)"
//// (which means it depends on internet and those external services' availability and it costs too), hence commented.
//class SmsSenderSpec extends TestKit(ActorSystem("test")) with BasicSpec with ImplicitSender {
//  val appConfig: AppConfig = AppConfigWrapper
//
//  val smsSender: ActorRef = system.actorOf(Props(new DefaultSMSSender(appConfig)))
//  val duration: FiniteDuration = Duration.create(15, TimeUnit.SECONDS)
//
//  "Default Sms Sender" - {
//    "when tried to send sms" - {
//      "should be able to send it successfully" in {
//        smsSender ! SmsInfo("4045943696", "test msg")
//        expectMsgPF(duration) {
//          case ss: SmsSent =>
//            println("smsSent: " + ss)
//        }
//      }
//    }
//  }
//}
