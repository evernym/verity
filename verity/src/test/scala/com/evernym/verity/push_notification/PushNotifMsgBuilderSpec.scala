package com.evernym.verity.push_notification

import com.evernym.verity.actor.agent.msghandler.outgoing.NotifyMsgDetail
import com.evernym.verity.actor.testkit.actor.MockAppConfig
import com.evernym.verity.constants.Constants._
import com.evernym.verity.did.DidStr
import com.evernym.verity.testkit.{BasicSpec, CancelGloballyAfterFailure}


class PushNotifMsgBuilderSpec extends BasicSpec with CancelGloballyAfterFailure with PushNotifMsgBuilder with MockAppConfig {

  override def msgRecipientDID: DidStr = "msgRecipientDID"

  val cm = "cm"
  val regId = "regId"
  val newExtraData = Map(
    NAME_KEY -> "Remote connection",
    PUSH_NOTIF_BODY_TEMPLATE -> "#{senderName} sent you #{msgType}"
  )

  "PushNotifMsgBuilder" - {
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/offer-credential", "Remote connection sent you a Credential Offer")
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/issue-credential", "Remote connection sent you a Credential")
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/request-presentation", "Remote connection sent you a Proof Request")
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/committedanswer/1.0/question", "Remote connection sent you a Question")
    checkPushMsgData("did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/unknown-protocol/1.0/proto-msg", "Remote connection sent you a Message", expAlertPushNotif = false)
    checkPushMsgData("https://didcomm.org/issue-credential/1.0/offer-credential", "Remote connection sent you a Credential Offer")
    checkPushMsgData("https://didcomm.org/issue-credential/1.0/issue-credential", "Remote connection sent you a Credential")
    checkPushMsgData("https://didcomm.org/present-proof/1.0/request-presentation", "Remote connection sent you a Proof Request")
    checkPushMsgData("https://didcomm.org/committedanswer/1.0/question", "Remote connection sent you a Question")
    checkPushMsgData("https://didcomm.org/unknown-protocol/1.0/proto-msg", "Remote connection sent you a Message", expAlertPushNotif = false)
    checkPushMsgData("unknown", "Remote connection sent you a Message")
    checkPushMsgData("proofReq", "Remote connection sent you a Proof Request", "proofReq")
    checkPushMsgData("legacyType", "Remote connection sent you a Legacy Type", "legacyType", expAlertPushNotif = false)
    checkPushMsgData("invalid/type", "Remote connection sent you a Message", expAlertPushNotif = false)
  }

  def checkPushMsgData(msgType: String, expBody: String, expLegacyType: String = "unknown", expAlertPushNotif: Boolean = true): Unit = {
    s"should create correct push msg for type: $msgType" in {
      val spn = getCommonPushNotifData(NotifyMsgDetail("MsgId", msgType), newExtraData).get
      spn.notifData(BODY) shouldBe expBody
      spn.extraData(PUSH_NOTIF_MSG_TYPE) shouldBe msgType
      spn.extraData(TYPE) shouldBe expLegacyType // this is legacy field
      spn.sendAsAlertPushNotif shouldBe expAlertPushNotif
    }
  }

}

