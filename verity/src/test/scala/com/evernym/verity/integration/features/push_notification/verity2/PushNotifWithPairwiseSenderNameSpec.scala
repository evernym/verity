package com.evernym.verity.integration.features.push_notification.verity2

import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.{ComMethod, UpdateComMethodReqMsg, UpdateConfigReqMsg}
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.msg_listener.JsonMsgListener
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider, V1OAuthParam}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base.{CAS, PortProvider, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl.AskQuestion
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.typesafe.config.ConfigFactory
import org.json.JSONObject

import scala.concurrent.duration._
import scala.concurrent.Await


//when the agent configuration is updated with `senderName`
//     and a label is also provided during invite creation
// in this case the label provided in the invitation should be used as a 'senderName' in the push notification
class PushNotifWithPairwiseSenderNameSpec
  extends VerityProviderBaseSpec
  with SdkProvider {

  var issuerSDK: IssuerSdk = _
  var holderSDK: HolderSdk = _
  var issuerVerityEnv: VerityEnv = _
  var holderVerityEnv: VerityEnv = _

  val firstConn = "connId1"
  var firstInvitation: Invitation = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    val issuerVerityEnvFut = VerityEnvBuilder().buildAsync(VAS)
    val holderVerityEnvFut = VerityEnvBuilder().withConfig(CAS_CONFIG).buildAsync(CAS)

    val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnvFut, executionContext, Option(V1OAuthParam(5.seconds)))
    val holderSDKFut = setupHolderSdkAsync(holderVerityEnvFut, defaultSvcParam.ledgerTxnExecutor, defaultSvcParam.vdrTools, executionContext)

    issuerVerityEnv = Await.result(issuerVerityEnvFut, ENV_BUILD_TIMEOUT)
    holderVerityEnv = Await.result(holderVerityEnvFut, ENV_BUILD_TIMEOUT)

    issuerSDK = Await.result(issuerSDKFut, SDK_BUILD_TIMEOUT)
    holderSDK = Await.result(holderSDKFut, SDK_BUILD_TIMEOUT)

    issuerSDK.resetPlainMsgsCounter.plainMsgsBeforeLastReset shouldBe 0
    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhookWithoutOAuth()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "config-issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
    val receivedMsg = issuerSDK.sendCreateRelationship(firstConn, Option("issuer-name-conn1"))
    firstInvitation = issuerSDK.sendCreateConnectionInvitation(firstConn, receivedMsg.threadOpt)

    holderSDK.fetchAgencyKey()
    holderSDK.provisionVerityCloudAgent()
    holderSDK.registerWebhook(UpdateComMethodReqMsg(
      ComMethod("id", 1, s"FCM:localhost:${pushNotifListener.port}/webhook", None, None)
    ))
    holderSDK.sendCreateNewKey(firstConn)
    holderSDK.sendConnReqForInvitation(firstConn, firstInvitation)
    checkPushNotif { pushNotifJson =>
      val data = pushNotifJson.getJSONObject("data")
      val msgType = data.getString("msgType")
      msgType shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response"
    }
    issuerSDK.expectConnectionComplete(firstConn)
  }

  "IssuerSDK" - {
    "when tried to send 'ask-question' (questionanswer 1.0) message" - {
      "should be successful" in {
        issuerSDK.sendMsgForConn(firstConn, AskQuestion("How are you?", Option("detail"),
          Vector("I am fine", "I am not fine"), signature_required = false, None))
      }
    }
  }

  var lastReceivedMsgThread: Option[MsgThread] = None

  lazy val pushNotifListener = new JsonMsgListener(PortProvider.getFreePort, None)(holderSDK.system)

  "HolderSDK" - {
    "when tried to check push notification" - {
      "should receive it" in {
        checkPushNotif { pushNotifJson =>
          val data = pushNotifJson.getJSONObject("data")
          val msgType = data.getString("msgType")
          val pushNotifMsgTitle = data.getString("pushNotifMsgTitle")
          val pushNotifMsgText = data.getString("pushNotifMsgText")
          msgType shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/questionanswer/1.0/question"
          pushNotifMsgTitle shouldBe "Hi there"
          pushNotifMsgText shouldBe "issuer-name-conn1 sent you a question"
        }
      }
    }
  }

  private def checkPushNotif(check: JSONObject => Unit): Unit = {
    val msg = pushNotifListener.expectMsg(5.seconds)
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