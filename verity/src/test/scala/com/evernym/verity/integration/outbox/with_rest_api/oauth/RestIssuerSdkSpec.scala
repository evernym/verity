package com.evernym.verity.integration.outbox.with_rest_api.oauth

import akka.http.scaladsl.model.StatusCodes.Accepted
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.{OAuthParam, SdkProvider}
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{Create, PublicIdentifierCreated}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl.AskQuestion
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg.{Answer, Question}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerMsgFamily
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.{AnswerGiven, StatusReport => QAStatusReport}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Ctl.ConnectionInvitation
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Ctl.Update
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.Sig.ConfigResult
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.{Config => AgentConfig}
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.{Write, StatusReport => WSStatusReport}
import com.typesafe.config.{Config, ConfigFactory}
import java.util.UUID

import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._


class RestIssuerSdkSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  lazy val walletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext

  lazy val issuerVerityEnv = VerityEnvBuilder.default().withConfig(REST_API_CONFIG).build(VAS)
  lazy val holderVerityEnv = VerityEnvBuilder.default().build(CAS)

  lazy val issuerRestSDK = setupIssuerRestSdk(issuerVerityEnv, executionContext, walletExecutionContext, Option(OAuthParam(5.seconds)))
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, executionContext, walletExecutionContext)

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerRestSDK.msgListener.accessTokenRefreshCount shouldBe 0
    issuerRestSDK.resetPlainMsgsCounter.plainMsgsBeforeLastReset shouldBe 0
    issuerRestSDK.fetchAgencyKey()
    issuerRestSDK.provisionVerityEdgeAgent()    //this sends a packed message (not REST api call)
    issuerRestSDK.registerWebhookWithoutOAuth()
    issuerRestSDK.registerWebhook()
  }

  var lastReceivedThread: Option[MsgThread] = None
  val firstConn = "connId1"
  var firstInvitation: Invitation = _

  "IssuerRestSdk" - {
    "when sent POST update (update config 0.6) message" - {
      "should be successful" in {
        val lastThreadId = Option(MsgThread(Option(UUID.randomUUID().toString)))
        val msg = Update(Set(AgentConfig("name", "env-name"), AgentConfig("logoUrl", "env-logo-url")))
        val response = issuerRestSDK.sendMsg(msg, lastThreadId)
        response.status shouldBe Accepted
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[ConfigResult]()
        receivedMsgParam.msg.configs.size shouldBe 2
      }
    }

    "when sent GET status (update config 0.6) message" - {
      "should be successful" in {
        val configResult = issuerRestSDK.sendGetStatusReq[ConfigResult](lastReceivedThread)
        configResult.status shouldBe "OK"
        configResult.result.configs.size shouldBe 2
      }
    }

    "when sent POST create (issuer-setup 0.6) message" - {
      "should be successful" in {
        val msg = Create()
        val response = issuerRestSDK.sendMsg(msg)
        response.status shouldBe Accepted

        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[PublicIdentifierCreated]()
        receivedMsgParam.msg.identifier.did.nonEmpty shouldBe true
        receivedMsgParam.msg.identifier.verKey.nonEmpty shouldBe true
      }
    }

    "when sent POST write (write-schema 0.6) message" - {
      "should be successful" in {
        val msg = Write("schema-name", "1.0", Seq("firstName","lastName"))
        val response = issuerRestSDK.sendMsg(msg)
        response.status shouldBe Accepted
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[WSStatusReport]()
        receivedMsgParam.msg.schemaId.nonEmpty shouldBe true
      }
    }

    "when sent POST create (relationship 1.0) message" - {
      "should be successful" in {
        val receivedMsgParam = issuerRestSDK.sendCreateRelationship(firstConn)
        lastReceivedThread = receivedMsgParam.threadOpt
        receivedMsgParam.msg.did.nonEmpty shouldBe true
        receivedMsgParam.msg.verKey.nonEmpty shouldBe true
      }
    }

    "when sent POST connection-invitation (relationship 1.0) message" - {
      "should be successful" in {
        val msg = ConnectionInvitation()
        val response = issuerRestSDK.sendMsg(msg, lastReceivedThread)
        response.status shouldBe Accepted
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[Invitation]()
        receivedMsgParam.msg.inviteURL.nonEmpty shouldBe true
        firstInvitation = receivedMsgParam.msg
      }
    }
  }

  "HolderSDK" - {

    "when provisioned cloud agent" - {
      "should be successful" in {
        holderSDK.fetchAgencyKey()
        val created = holderSDK.provisionVerityCloudAgent()
        created.selfDID.nonEmpty shouldBe true
        created.agentVerKey.nonEmpty shouldBe true
      }
    }

    "when accepting first invitation" - {
      "should be successful" in {
        holderSDK.sendCreateNewKey(firstConn)
        holderSDK.sendConnReqForInvitation(firstConn, firstInvitation)
      }
    }
  }

  "IssuerSdk" - {
    "after user accepted invitation" - {
      "should receive notifications on webhook" in {
        val complete = issuerRestSDK.expectConnectionComplete(firstConn)
        complete.theirDid.isEmpty shouldBe false
      }
    }

    "when sent ask-question (questionanswer 1.0) message" - {
      "should be successful" in {
        val msg = AskQuestion("How are you?", Option("question-detail"),
          Vector("I am fine","I am not fine"), signature_required = false, None)
        val response = issuerRestSDK.sendMsgForConn(firstConn, msg)
        response.status shouldBe Accepted
      }
    }
  }

  "HolderSDK" - {
    "when tried to get newly un viewed messages" - {
      "should get 'question' (questionanswer 1.0) message" in {
        val receivedMsgParam = holderSDK.expectMsgFromConn[Question](firstConn)
        lastReceivedThread = receivedMsgParam.threadOpt
        holderSDK.sendUpdateMsgStatusAsReviewedForConn(firstConn, receivedMsgParam.msgId)
        val question = receivedMsgParam.msg
        question.question_text shouldBe "How are you?"
      }
    }

    "when sent 'answer' (questionanswer 1.0) message" - {
      "should be successful" in {
        val answer = Answer("I am fine", None, None)
        holderSDK.sendProtoMsgToTheirAgent(firstConn, answer, lastReceivedThread)
      }
    }
  }

  "IssuerSdk" - {
    "when tried to get newly un viewed messages" - {
      "should get 'answer' (questionanswer 1.0) message" in {
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[AnswerGiven]()
        receivedMsgParam.msg.answer shouldBe "I am fine"
      }
    }

    "when sent GET status (questionanswer 1.0)" - {
      "should be successful" in {
        val restOkResp = issuerRestSDK.sendGetStatusReqForConn[QAStatusReport](firstConn, QuestionAnswerMsgFamily, lastReceivedThread)
        restOkResp.status shouldBe "OK"
      }
    }

    "when checked oauth stats" - {
      "should be as expected" in {
        issuerRestSDK.resetPlainMsgsCounter.plainMsgsBeforeLastReset shouldBe 1
        issuerRestSDK.msgListener.accessTokenRefreshCount <= 2 shouldBe true
      }
    }
  }

  val REST_API_CONFIG: Config =
    ConfigFactory.parseString(
      """
         verity.rest-api.enabled = true
        """.stripMargin
    )

  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}
