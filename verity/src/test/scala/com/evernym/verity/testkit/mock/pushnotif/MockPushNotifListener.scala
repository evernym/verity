package com.evernym.verity.testkit.mock.pushnotif

import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.constants.Constants.COM_METHOD_TYPE_PUSH
import com.evernym.verity.actor.agent.user.ComMethodDetail
import com.evernym.verity.config.AppConfig
import com.evernym.verity.push_notification.PusherUtil
import com.evernym.verity.testkit.BasicSpecBase
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

trait MockPushNotifListener extends HasExecutionContextProvider {

  this: BasicSpecBase with Eventually =>

  def withExpectNewPushNotif[T](atAddress: String, f : => T): (T, Option[PushNotifPayload]) = {
    val mockComMethod = ComMethodDetail(COM_METHOD_TYPE_PUSH, atAddress, hasAuthEnabled = false)
    val actualAddress = PusherUtil.extractServiceProviderAndRegId(mockComMethod, appConfig, futureExecutionContext)._2
    val (currentCount, _) = getLatestPushNotifPayload(actualAddress)
    val result = f
    val lastPayload = checkForNewPushNotifPayload(actualAddress, currentCount)
    (result, lastPayload)
  }

  private def getLatestPushNotifPayload(forAddress: String): (Int, Option[PushNotifPayload]) = {
    val count = MockFirebasePusher.pushedMsg.get(forAddress).map(_.allNotifs.size).getOrElse(0)
    (count, MockFirebasePusher.pushedMsg.get(forAddress).map(_.lastPushNotifPayload))
  }

  private def checkForNewPushNotifPayload(atAddress: String, currentPushNotifCount: Int): Option[PushNotifPayload] = {
    //this confirms that protocol does sent a message to registered push notif
    eventually (timeout(Span(15, Seconds))) {
      val (count, lastPayloadOpt) = getLatestPushNotifPayload(atAddress)
      count shouldBe currentPushNotifCount + 1
      lastPayloadOpt
    }
  }

  def appConfig: AppConfig

  val validTestPushNotifToken = s"${MockFirebasePusher.comMethodPrefix}:http://test.push-notif.com"

}
