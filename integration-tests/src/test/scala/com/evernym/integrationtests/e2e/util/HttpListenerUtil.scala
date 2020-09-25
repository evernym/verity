package com.evernym.integrationtests.e2e.util

import com.evernym.verity.testkit.Matchers
import com.evernym.verity.testkit.util.http_listener.PushNotifMsgHttpListener
import org.scalatest.concurrent.Eventually

trait HttpListenerUtil extends Matchers with Eventually {
  def withLatestPushMessage(pushNotifListener: PushNotifMsgHttpListener, f: => Unit): String = {
    pushNotifListener.getAndResetReceivedMsgs
    f
    eventually {
      val latestMsgOpt = pushNotifListener.getAndResetReceivedMsgs.headOption
      latestMsgOpt.isDefined shouldBe true
      latestMsgOpt.get
    }
  }

}
