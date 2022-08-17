package com.evernym.verity.integration.push_notification.verity1

import com.evernym.verity.actor.ConnectionStatusUpdated
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_MSG_PACK
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.{ComMethod, UpdateComMethodReqMsg, UpdateConfigReqMsg}
import com.evernym.verity.constants.Constants.NO
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.msg_listener.JsonMsgListener
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, JsonMsgUtil, SdkProvider, VerifierSdk}
import com.evernym.verity.integration.base.{CAS, EAS, PortProvider, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.util2.Status
import com.typesafe.config.ConfigFactory
import org.json.JSONObject

import scala.concurrent.duration._


class VariousPushNotifsSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  val issuerVerityEnv = VerityEnvBuilder().build(EAS)
  val verifierVerityEnv = VerityEnvBuilder().build(EAS)
  val holderVerityEnv = VerityEnvBuilder().withConfig(CAS_CONFIG).build(CAS)

  var issuerSDK: IssuerSdk = setupIssuerSdk(issuerVerityEnv, executionContext)
  var verifierSDK: VerifierSdk = setupVerifierSdk(verifierVerityEnv, executionContext)
  var holderSDK: HolderSdk = setupHolderSdk(holderVerityEnv, executionContext, defaultSvcParam.ledgerTxnExecutor)

  val issuerHolderConn = "connId1"
  val verifierHolderConn = "connId2"
  var invitation: InviteDetail = null

  var lastReceivedThread: Option[MsgThread] = None

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionAgent_0_5()
    issuerSDK.updateComMethod_0_5(issuerSDK.msgListener.webhookEndpoint)
    //change the issuer's name if needed
    issuerSDK.sendUpdateConfig(
      UpdateConfigReqMsg(Set(ConfigDetail("name", "config-issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))

    verifierSDK.fetchAgencyKey()
    verifierSDK.provisionAgent_0_5()
    verifierSDK.updateComMethod_0_5(verifierSDK.msgListener.webhookEndpoint)
    //change the issuer's name if needed
    verifierSDK.sendUpdateConfig(
      UpdateConfigReqMsg(Set(ConfigDetail("name", "config-issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))


    holderSDK.fetchAgencyKey()
    holderSDK.provisionAgent_0_6()
    holderSDK.registerWebhook(UpdateComMethodReqMsg(
      ComMethod("id", 1, s"FCM:localhost:${pushNotifListener.port}/webhook", None, None)
    ))

    //establish connection between issuer and holder
    issuerSDK.createKey_0_5(issuerHolderConn)
    invitation = issuerSDK.sendConnReq_0_5(issuerHolderConn).md.inviteDetail
    holderSDK.createKey_0_6(issuerHolderConn)
    val holderDidPairForIssuer = holderSDK.sendConnReqAnswer_0_5(issuerHolderConn, invitation)
    issuerSDK.expectMsgOnWebhook[ConnectionStatusUpdated](mpf = MPF_MSG_PACK)
    issuerSDK.processConnectionCompleted(issuerHolderConn, holderDidPairForIssuer)
    checkHolderPushNotif { pushNotifJson =>
      val data = pushNotifJson.getJSONObject("data")
      val msgType = data.getString("msgType")
      val legacyType = data.getString("type")
      val pushNotifMsgText = data.getString("pushNotifMsgText")
      msgType shouldBe "success"
      legacyType shouldBe "connReqAnswer"
      pushNotifMsgText.startsWith("Remote connection responded with successful response") shouldBe true
    }

    //establish connection between verifier and holder
    verifierSDK.createKey_0_5(verifierHolderConn)
    invitation = verifierSDK.sendConnReq_0_5(verifierHolderConn).md.inviteDetail
    holderSDK.createKey_0_6(verifierHolderConn)
    val holderDidPairForVerifier = holderSDK.sendConnReqAnswer_0_5(verifierHolderConn, invitation)
    verifierSDK.expectMsgOnWebhook[ConnectionStatusUpdated](mpf = MPF_MSG_PACK)
    verifierSDK.processConnectionCompleted(verifierHolderConn, holderDidPairForVerifier)
    checkHolderPushNotif { pushNotifJson =>
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
        val packedMsg = issuerSDK.packForTheirPairwiseRel(issuerHolderConn, jsonObject.toString)
        issuerSDK.sendCreateMsgReq_0_5(issuerHolderConn, packedMsg, Option("pairwise-issuer-name"))
      }
    }
  }

  "HolderSDK" - {
    "when tried to get newly un viewed messages" - {
      "should get 'question' (questionanswer 1.0) message" in {

        checkHolderPushNotif { pushNotifJson =>
          val data = pushNotifJson.getJSONObject("data")
          val msgType = data.getString("msgType")
          val legacyType = data.getString("type")
          val pushNotifMsgText = data.getString("pushNotifMsgText")
          msgType shouldBe "general"
          legacyType shouldBe "general"
          pushNotifMsgText shouldBe "pairwise-issuer-name sent you a general"
        }

        val receivedMsg = holderSDK.downloadMsg[Question]("general", Option(NO),
          Option(List(Status.MSG_STATUS_RECEIVED.statusCode)), connId=Option(issuerHolderConn))
        lastReceivedThread = receivedMsg.threadOpt
        val question = receivedMsg.msg
        question.text shouldBe "How are you?"
      }
    }
  }

  lazy val pushNotifListener = new JsonMsgListener(PortProvider.getFreePort, None)(holderSDK.system)

  private def checkHolderPushNotif(check: JSONObject => Unit): Unit = {
    val msg = pushNotifListener.expectMsg(20.seconds)
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
