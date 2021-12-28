package com.evernym.verity.integration.outbox.with_sdk.oauth

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
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._


class QuestionAnswerSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val issuerVerityEnvFut = VerityEnvBuilder.default().buildAsync(VAS)
  lazy val holderVerityEnvFut = VerityEnvBuilder.default().buildAsync(CAS)

  lazy val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnvFut, executionContext, Option(V1OAuthParam(5.seconds)))
  lazy val holderSDKFut = setupHolderSdkAsync(holderVerityEnvFut, defaultSvcParam.ledgerTxnExecutor, executionContext)
  var issuerSDK: IssuerSdk = _
  var holderSDK: HolderSdk = _
  var issuerVerityEnv: VerityEnv = _
  var holderVerityEnv: VerityEnv = _

  val firstConn = "connId1"
  var firstInvitation: Invitation = _

  override def beforeAll(): Unit = {
    super.beforeAll()


    val f1 = issuerVerityEnvFut
    val f2 = holderVerityEnvFut
    val f3 = issuerSDKFut
    val f4 = holderSDKFut

    issuerVerityEnv = Await.result(f1, ENV_BUILD_TIMEOUT)
    holderVerityEnv = Await.result(f2, ENV_BUILD_TIMEOUT)

    issuerSDK = Await.result(f3, SDK_BUILD_TIMEOUT)
    holderSDK = Await.result(f4, SDK_BUILD_TIMEOUT)

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
        val receivedMsg = holderSDK.expectMsgFromConn[Question](firstConn)
        lastReceivedMsgThread = receivedMsg.threadOpt
        val question = receivedMsg.msg
        question.question_text shouldBe "How are you?"
        holderSDK.sendUpdateMsgStatusAsReviewedForConn(firstConn, receivedMsg.msgId)
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
        val receivedMsg = holderSDK.expectMsgFromConn[Question](firstConn)
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

  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}
