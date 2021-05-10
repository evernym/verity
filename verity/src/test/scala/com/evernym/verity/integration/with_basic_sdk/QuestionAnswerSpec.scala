package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.integration.base.VerityAppBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.connecting.common.ConnReqReceived
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{Complete, ConnResponseSent}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl.AskQuestion
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg.{Answer, Question}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.AnswerGiven
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation


class QuestionAnswerSpec
  extends VerityAppBaseSpec
    with SdkProvider {

  lazy val verityVAS = setupNewVerityApp()
  lazy val verityCAS = setupNewVerityApp()

  lazy val issuerSDK = setupIssuerSdk(verityVAS.platform)
  lazy val holderSDK = setupHolderSdk(verityCAS.platform)

  lazy val allVerityApps: List[HttpServer] = List(verityVAS, verityCAS)

  val firstConn = "connId1"
  var firstInvitation: Invitation = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    issuerSDK.fetchAgencyKey()
    issuerSDK.provisionVerityEdgeAgent()
    issuerSDK.registerWebhook()
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "issuer-name"), ConfigDetail("logoUrl", "issuer-logo-url"))))
    issuerSDK.sendCreateRelationship(firstConn)
    firstInvitation = issuerSDK.sendCreateConnectionInvitation(firstConn)

    holderSDK.fetchAgencyKey()
    holderSDK.provisionVerityCloudAgent()
    holderSDK.sendCreateNewKey(firstConn)
    holderSDK.sendConnReqForUnacceptedInvitation(firstConn, firstInvitation)

    issuerSDK.expectMsgOnWebhook[ConnReqReceived]
    issuerSDK.expectMsgOnWebhook[ConnResponseSent]
    issuerSDK.expectMsgOnWebhook[Complete]
  }

  "IssuerSDK" - {
    "when tried to send 'ask-question' message" - {
      "should be successful" in {
        issuerSDK.sendControlMsgToConnection(firstConn, AskQuestion("How are you?", Option("detail"), Vector("I am fine", "I am not fine"), signature_required = false, None))
      }
    }
  }

  var lastReceivedMsgThreadId: Option[ThreadId] = None

  "HolderSDK" - {
    "when tried to get newly un viewed messages" - {
      "should get 'question' message" in {
        val receivedMsg = holderSDK.expectMsgOnConn[Question](firstConn)
        lastReceivedMsgThreadId = receivedMsg.threadId
        val question = receivedMsg.msg
        question.question_text shouldBe "How are you?"
      }
    }

    "when tried to respond with 'answer' message" - {
      "should be successful" in {
        val answer = Answer("I am fine", None, None)
        holderSDK.sendProtoMsgToTheirAgent(firstConn, answer, lastReceivedMsgThreadId)
      }
    }
  }

  "IssuerSDK" - {
    "should receive 'answer' message" in {
      val receivedMsg = issuerSDK.expectMsgOnWebhook[AnswerGiven]
      receivedMsg.msg.answer shouldBe "I am fine"
    }
  }
}