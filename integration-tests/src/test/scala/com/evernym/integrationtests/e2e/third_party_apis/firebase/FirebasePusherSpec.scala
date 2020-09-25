//package com.evernym.integrationtests.e2e.third_party_apis.firebase
//
//import akka.testkit.TestKit
//import com.evernym.verity.Constants._
//import com.evernym.verity.actor.testkit.AkkaTestBasic
//import com.evernym.verity.push_notification.FirebasePusher
//import com.evernym.verity.testkit.BasicSpec

//
//import scala.concurrent.Await
//import scala.concurrent.duration.Duration
//
//class FirebasePusherSpec extends TestKit(AkkaTestBasic.system()) with BasicSpec {
//
//  val extraData = Map(FOR_DID -> "test-ep-id", "data" -> """{"credOffer":{"name":"Home Address","version":"1.0.0","revealedAttributes":[{"label":"Address 1","data":"An Address"},{"label":"Address 2","data":"An Address 2"}]},"issuer":{"name":"Test Issuer","logoUrl":"https://example.com/agent/profile/logo","pairwiseDID":"ha66899sadfjZJGINKN0770"}}""")
//  val notifData = Map(BODY -> "notif-body", BADGE_COUNT -> 1)
//  val regTokenId = "c9Opl8rgyRs:APA91bF73AeSM5_godAIs-s3CzucLDcIDrz8Idfb9X6CQlhMoBkeqBK9tB7SgY8jiFN6eQheDqdiwzl9kNSh36jQ4XzrnjvlfO-eGPE7nphYEimt-r9QdIThVpta3fi5x0_nZrRhLV_Z"
//
//  "FCM pusher" - {
//    /*Note: This test depends on real pusher object which has dependency on internet's availability
//    which doesn't look right expectation from tests, for now, commenting it.*/
//    "when asked to send push notification" - {
//      "should send push notification" in {
//        val response = Await.result(FirebasePusher.push("cm", regTokenId, sendAsAlertPushNotif = false,
//          notifData, extraData)(system), Duration(5, "seconds"))
//        println("### response: " + response)
//      }
//    }
//  }
//}
