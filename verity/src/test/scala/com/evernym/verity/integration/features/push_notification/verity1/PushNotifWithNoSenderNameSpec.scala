package com.evernym.verity.integration.features.push_notification.verity1

import com.evernym.verity.actor.ConnectionStatusUpdated
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.agentmsg.msgfamily.configs.{ComMethod, UpdateComMethodReqMsg}
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.msg_listener.JsonMsgListener
import com.evernym.verity.integration.base.sdk_provider.{JsonMsgUtil, SdkProvider}
import com.evernym.verity.integration.base.{CAS, EAS, PortProvider, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail

import com.typesafe.config.ConfigFactory
import org.json.JSONObject

import scala.concurrent.duration._

// when senderName is neither set in the agent config nor provided/overridden explicitly
// in this case the 'Remote connection' should be the 'senderName' in the push notification

class PushNotifWithNoSenderNameSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerEAS = VerityEnvBuilder().build(EAS)
  lazy val holderCAS = VerityEnvBuilder().build(CAS)

  lazy val issuerSDKEAS = setupIssuerSdk(issuerEAS, executionContext)
  lazy val holderSDKCAS = setupHolderSdk(holderCAS, executionContext, defaultSvcParam.ledgerTxnExecutor, defaultSvcParam.vdrTools)
  val connId = "connId1"
  var invitation: InviteDetail = null

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDKEAS.fetchAgencyKey()
    issuerSDKEAS.provisionAgent_0_5()
    issuerSDKEAS.updateComMethod_0_5(issuerSDKEAS.msgListener.webhookEndpoint)
    issuerSDKEAS.createKey_0_5(connId)
    invitation = issuerSDKEAS.sendConnReq_0_5(connId).md.inviteDetail

    holderSDKCAS.fetchAgencyKey()
    holderSDKCAS.provisionAgent_0_6()
    holderSDKCAS.registerWebhook(UpdateComMethodReqMsg(
      ComMethod("id", 1, s"FCM:localhost:${pushNotifListener.port}/webhook", None, None)
    ))
    holderSDKCAS.createKey_0_6(connId)
    val myDidPair = holderSDKCAS.sendConnReqAnswer_0_5(connId, invitation)

    issuerSDKEAS.expectMsgOnWebhook[ConnectionStatusUpdated](mpf = MPF_MSG_PACK)
    issuerSDKEAS.processConnectionCompleted(connId, myDidPair)
    checkPushNotif { pushNotifJson =>
      val data = pushNotifJson.getJSONObject("data")
      val msgType = data.getString("msgType")
      val legacyType = data.getString("type")
      val pushNotifMsgText = data.getString("pushNotifMsgText")
      msgType shouldBe "success"
      legacyType shouldBe "connReqAnswer"
      pushNotifMsgText.startsWith("Remote connection responded with successful response") shouldBe true
    }
  }

  "IssuerSDK" - {
    "when tried to send 'ask-question' (questionanswer 1.0) message" - {
      "should be successful" in {
        val msg = AskQuestion("How are you?", Option("detail"),
          Vector("I am fine", "I am not fine"), signature_required = false)
        val jsonObject = new JSONObject(JsonMsgUtil.createJsonString("", msg))
        val packedMsg = issuerSDKEAS.packForTheirPairwiseRel(connId, jsonObject.toString)
        issuerSDKEAS.sendCreateMsgReq_0_5(connId, packedMsg, None)
      }
    }
  }

  var lastReceivedMsgThread: Option[MsgThread] = None

  lazy val pushNotifListener = new JsonMsgListener(PortProvider.getFreePort, None)(holderSDKCAS.system)

  "HolderSDK" - {
    "when tried to check push notification" - {
      "should receive it" in {
        checkPushNotif { pushNotifJson =>
          val data = pushNotifJson.getJSONObject("data")
          val msgType = data.getString("msgType")
          val legacyType = data.getString("type")
          val pushNotifMsgTitle = data.getString("pushNotifMsgTitle")
          val pushNotifMsgText = data.getString("pushNotifMsgText")
          msgType shouldBe "general"
          legacyType shouldBe "general"
          pushNotifMsgTitle shouldBe "Hi there"
          pushNotifMsgText.startsWith("Remote connection sent you a general") shouldBe true
        }
      }
    }
  }

  private def checkPushNotif(check: JSONObject => Unit): Unit = {
    val msg = pushNotifListener.expectMsg(125.seconds)
    val pushNotif = new JSONObject(msg)
    check(pushNotif)
  }

  lazy val CAS_CONFIG = ConfigFactory.parseString(
    s"""
       |verity.services.push-notif-service {
       | enabled = true
       | fcm {
       |   provider = "com.evernym.verity.testkit.mock.pushnotif.MockFirebasePusher"
       |   send-messages-to-endpoint = true
       | }
       |}
       |""".stripMargin)
}