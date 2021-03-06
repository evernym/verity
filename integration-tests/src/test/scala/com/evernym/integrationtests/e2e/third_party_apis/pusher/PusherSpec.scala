package com.evernym.integrationtests.e2e.third_party_apis.pusher

import akka.actor.ActorRef
import com.evernym.verity.actor.agent.relationship.EndpointType
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.actor.testkit.{ActorSpec, AppStateManagerTestKit}
import com.evernym.verity.config.AppConfigWrapper
import com.evernym.verity.push_notification.{Pusher, SendPushNotif}
import com.evernym.verity.testkit.BasicSpec

class PusherSpec
  extends ActorSpec
    with BasicSpec {

  val asmTestKit = new AppStateManagerTestKit(this)

  lazy val pusher: ActorRef = system.actorOf(Pusher.props(AppConfigWrapper), s"pusher-1")

  "Pusher" - {
    "when sent 'SendPushNotif' command with 'InvalidRegistration' token" - {
      "should not change app state" in {
        val currentAppState = asmTestKit.currentAppState
        val cmd = ComMethodDetail(EndpointType.PUSH, "FCM:12345")
        val spn = SendPushNotif(Set(cmd), sendAsAlertPushNotif = true, Map.empty, Map.empty)
        pusher ! spn
        Thread.sleep(5000)
        asmTestKit shouldBe currentAppState
      }
    }

    "when sent 'SendPushNotif' command with 'NotRegistered' token" - {
      "should not change app state" in {
        val currentAppState = asmTestKit.currentAppState
        val cmd = ComMethodDetail(EndpointType.PUSH, "FCM:c5zcR5qjD3k:APA91bHwCoqBJCcUS0JrWYlQy6cvvoHnjAukQjHVAfbjQvp5zgof7v1rX6yt10qWVKd7BshK_M5y3h7RbSte35TeEGHz9BriMCpYZbzXvHToMbFdYDHRQS8c0PIyoFChaHswmey2mpV6")
        val spn = SendPushNotif(Set(cmd), sendAsAlertPushNotif = true, Map.empty, Map.empty)
        pusher ! spn
        Thread.sleep(5000)
        asmTestKit.currentAppState shouldBe currentAppState
      }
    }
  }
}