package com.evernym.integrationtests.e2e.third_party_apis.firebase

import akka.testkit.TestKit
import com.evernym.integrationtests.e2e.util.TestExecutionContextProvider
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.config.ConfigConstants.{FCM_API_HOST, FCM_API_KEY, FCM_API_PATH}
import com.evernym.verity.constants.Constants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.push_notification.{FirebasePushServiceParam, FirebasePusher, PushNotifParam}
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.scalalogging.Logger

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class FirebasePusherSpec extends TestKit(AkkaTestBasic.system()) with BasicSpec {

  val logger: Logger = getLoggerByClass(getClass)

  val extraData = Map(FOR_DID -> "test-ep-id", "data" -> """{"credOffer":{"name":"Home Address","version":"1.0.0","revealedAttributes":[{"label":"Address 1","data":"An Address"},{"label":"Address 2","data":"An Address 2"}]},"issuer":{"name":"Test Issuer","logoUrl":"https://example.com/agent/profile/logo","pairwiseDID":"ha66899sadfjZJGINKN0770"}}""")
  val notifData = Map(BODY -> "notif-body", BADGE_COUNT -> 1)
  val regTokenId = "c9Opl8rgyRs:APA91bF73AeSM5_godAIs-s3CzucLDcIDrz8Idfb9X6CQlhMoBkeqBK9tB7SgY8jiFN6eQheDqdiwzl9kNSh36jQ4XzrnjvlfO-eGPE7nphYEimt-r9QdIThVpta3fi5x0_nZrRhLV_Z"

  val serverKey: String = AppConfigWrapper.getStringReq(FCM_API_KEY)
  val serverHost: String = AppConfigWrapper.getStringReq(FCM_API_HOST)
  val serverPath: String = AppConfigWrapper.getStringReq(FCM_API_PATH)
  val pusher =
    new FirebasePusher(
      FirebasePushServiceParam(serverKey, serverHost, serverPath),
      TestExecutionContextProvider.ecp.futureExecutionContext
    )

  "FCM pusher" - {
    /*Note: This test depends on real pusher object which has dependency on internet's availability
    which doesn't look right expectation from tests, for now, commenting it.*/
    "when asked to send push notification" - {
      "should send push notification" in {
        val param = PushNotifParam("cm", regTokenId, sendAsAlertPushNotif = false,
          notifData, extraData)
        val response = Await.result(pusher.push(param)(system), Duration(5, "seconds"))
        logger.info("response: " + response)
      }
    }
  }
}
