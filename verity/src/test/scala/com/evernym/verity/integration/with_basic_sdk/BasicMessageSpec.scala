package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.actor.agent.{Thread => MsgThread}
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Ctl.SendMessage
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Msg.Message
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation


class BasicMessageSpec extends VerityProviderBaseSpec
  with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder.default().build()
  lazy val holderVerityEnv = VerityEnvBuilder.default().build()

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor)

  val firstConn = "connId1"
  var firstInvitation: Invitation = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
    val receivedMsg = issuerSDK.sendCreateRelationship(firstConn)
    firstInvitation = issuerSDK.sendCreateConnectionInvitation(firstConn, receivedMsg.threadOpt)

    holderSDK.fetchAgencyKey()
    holderSDK.provisionVerityCloudAgent()
    holderSDK.sendCreateNewKey(firstConn)
    holderSDK.sendConnReqForInvitation(firstConn, firstInvitation)

    issuerSDK.expectConnectionComplete(firstConn)
  }

  "IssuerSDK" - {
    "when tried to send 'send-message' (basicmessage 1.0) message" - {
      "should be successful" in {
        issuerSDK.sendMsgForConn(firstConn, SendMessage(sent_time = "", content = "How are you?"))
      }
    }
  }

  var lastReceivedMsgThread: Option[MsgThread] = None

  "HolderSDK" - {
    "when tried to get newly un viewed messages" - {
      "should get 'message' (basicmessage 1.0) message" in {
        val receivedMsg = holderSDK.expectMsgFromConn[Message](firstConn)
        lastReceivedMsgThread = receivedMsg.threadOpt
        val message = receivedMsg.msg
        message.content shouldBe "How are you?"
        holderSDK.sendUpdateMsgStatusAsReviewedForConn(firstConn, receivedMsg.msgId)
      }
    }

    "when tried to respond with 'message' (basicmessage 1.0) message" - {
      "should be successful" in {
        val answer = Message(sent_time = "", content = "I am fine.")
        holderSDK.sendProtoMsgToTheirAgent(firstConn, answer, lastReceivedMsgThread)
      }
    }
  }

  "IssuerSDK" - {
    "should receive 'message' (basicmessage 1.0) message" in {
      issuerSDK.expectMsgOnWebhook[Message]()
    }
  }

}
