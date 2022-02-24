package com.evernym.verity.push_notification

import com.evernym.verity.actor.agent.msghandler.outgoing.NotifyMsgDetail
import com.evernym.verity.actor.testkit.actor.MockAppConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.did.DidStr
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}


class PushNotifMsgBuilderSpec
  extends BasicSpec
    with CancelGloballyAfterFailure
    with PushNotifMsgBuilder
    with MockAppConfig {

  override def msgRecipientDID: DidStr = "msgRecipientDID"

  "PushNotifMsgBuilder" - {
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/offer-credential", "Remote connection sent you a credential offer")
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/issue-credential", "Remote connection sent you a credential")
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/request-presentation", "Remote connection sent you a proof request")
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/committedanswer/1.0/question", "Remote connection sent you a question")
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/unknown-protocol/1.0/proto-msg", "Remote connection sent you a message", expAlertPushNotif = false)
    checkPushMsgData("https://didcomm.org/issue-credential/1.0/offer-credential", "Remote connection sent you a credential offer")
    checkPushMsgData("https://didcomm.org/issue-credential/1.0/issue-credential", "Remote connection sent you a credential")
    checkPushMsgData("https://didcomm.org/present-proof/1.0/request-presentation", "Remote connection sent you a proof request")
    checkPushMsgData("https://didcomm.org/committedanswer/1.0/question", "Remote connection sent you a question")
    checkPushMsgData("https://didcomm.org/unknown-protocol/1.0/proto-msg", "Remote connection sent you a message", expAlertPushNotif = false)
    checkPushMsgData("unknown", "Remote connection sent you a message")
    checkPushMsgData("proofReq", "Remote connection sent you a proof request", "proofReq")
    checkPushMsgData("legacyType", "Remote connection sent you a legacy type", "legacyType", expAlertPushNotif = false)
    checkPushMsgData("invalid/type", "Remote connection sent you a message", expAlertPushNotif = false)
  }

  private def checkPushMsgData(msgType: String,
                               expBody: String,
                               expLegacyType: String = "unknown",
                               expAlertPushNotif: Boolean = true): Unit = {
    s"should create correct push msg for type: $msgType" in {
      val spn = buildPushNotifData(NotifyMsgDetail("MsgId", msgType, None), newExtraData).get
      spn.notifData(BODY) shouldBe expBody
      spn.extraData(PUSH_NOTIF_MSG_TYPE) shouldBe msgType
      spn.extraData(TYPE) shouldBe expLegacyType // this is legacy field
      spn.sendAsAlertPushNotif shouldBe expAlertPushNotif
    }
  }


  val newExtraData = Map(
    NAME_KEY -> "Remote connection",
    PUSH_NOTIF_BODY_TEMPLATE -> "#{senderName} sent you #{msgType}"
  )
}

