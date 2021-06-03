package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.actor.agent.{Thread => MsgThread}
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl.AskQuestion
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg.{Answer, Question}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.AnswerGiven
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.typesafe.config.ConfigFactory


class QuestionAnswerSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder.default().withConfig(VAS_CONFIG).build()
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
    }
  }

  val VAS_CONFIG = ConfigFactory.parseString {
    """
      |verity.retention-policy.default {
      | undefined-fallback {
      |   expire-after-days = 3 day
      |   expire-after-terminal-state = true
      | }
      |}
      |""".stripMargin
  }
}