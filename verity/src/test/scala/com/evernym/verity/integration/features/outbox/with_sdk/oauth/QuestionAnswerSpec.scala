package com.evernym.verity.integration.features.outbox.with_sdk.oauth

import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider, V1OAuthParam}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl.AskQuestion
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg.{Answer, Question}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.AnswerGiven
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation

import scala.concurrent.Await
import scala.concurrent.duration._


class QuestionAnswerSpec
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
    val holderVerityEnvFut = VerityEnvBuilder().buildAsync(CAS)

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
    "when tried to send 'ask-question' (questionanswer 1.0) message" - {
      "should be successful" in {
        issuerSDK.sendMsgForConn(firstConn, AskQuestion("How are you?", Option("detail"), Vector("I am fine", "I am not fine"), signature_required = false, None))
      }
    }
  }

  var lastReceivedMsgThread: Option[MsgThread] = None

  "HolderSDK" - {
    "when tried to get newly un viewed messages" - {
      "should get 'question' (questionanswer 1.0) message" in {
        val receivedMsg = holderSDK.downloadMsg[Question](firstConn)
        lastReceivedMsgThread = receivedMsg.threadOpt
        val question = receivedMsg.msg
        question.question_text shouldBe "How are you?"
      }
    }

    "when tried to respond with 'answer' (questionanswer 1.0) message" - {
      "should be successful" in {
        val answer = Answer("I am fine", None, None)
        holderSDK.sendProtoMsgToTheirAgent(firstConn, answer, lastReceivedMsgThread)
      }
    }
  }

  "IssuerSDK" - {
    "should receive 'answer-given' (questionanswer 1.0) message" in {
      val receivedMsg = issuerSDK.expectMsgOnWebhook[AnswerGiven]()
      receivedMsg.msg.answer shouldBe "I am fine"
    }
  }

  "VerityAdmin" - {
    "when restarts verity instances" - {
      "should be successful" in {
        issuerVerityEnv.restartAllNodes()
        holderVerityEnv.restartAllNodes()
        issuerSDK.fetchAgencyKey()
        holderSDK.fetchAgencyKey()
      }
    }
  }

  "IssuerSDK" - {
    "when tried to send 'ask-question' (questionanswer 1.0) message with new thread" - {
      "should be successful" in {
        val msg = AskQuestion("How are you after restart?", Option("detail"),
          Vector("I am fine", "I am not fine"), signature_required = false, None)
        issuerSDK.sendMsgForConn(firstConn, msg)
      }
    }
  }

  "HolderSDK" - {
    "when tried to get newly un viewed messages after restart" - {
      "should get 'question' (questionanswer 1.0) message" in {
        val receivedMsg = holderSDK.downloadMsg[Question](firstConn)
        lastReceivedMsgThread = receivedMsg.threadOpt
        val question = receivedMsg.msg
        question.question_text shouldBe "How are you after restart?"
      }
    }

    "when tried to respond with 'answer' (questionanswer 1.0) message after restart" - {
      "should be successful" in {
        val answer = Answer("I am fine after restart too", None, None)
        holderSDK.sendProtoMsgToTheirAgent(firstConn, answer, lastReceivedMsgThread)
      }
    }
  }

  "IssuerSDK" - {
    "should receive 'answer' (questionanswer 1.0) message after restart" in {
      val receivedMsg = issuerSDK.expectMsgOnWebhook[AnswerGiven]()
      receivedMsg.msg.answer shouldBe "I am fine after restart too"
      issuerSDK.resetPlainMsgsCounter.plainMsgsBeforeLastReset shouldBe 0
    }
  }
}
