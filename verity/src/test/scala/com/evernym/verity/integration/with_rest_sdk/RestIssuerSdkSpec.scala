package com.evernym.verity.integration.with_rest_sdk

import akka.http.scaladsl.model.StatusCodes.Accepted
import com.evernym.verity.app_launcher.HttpServer
import com.evernym.verity.integration.base.VerityAppBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.protocol.engine.ThreadId
import com.evernym.verity.protocol.protocols.connecting.common.ConnReqReceived
import com.evernym.verity.protocol.protocols.connections.v_1_0.Signal.{Complete, ConnResponseSent}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.{IssuerSetupMsgFamily, PublicIdentifierCreated}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg.{Answer, Question}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.QuestionAnswerMsgFamily
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.{AnswerGiven, StatusReport => QAStatusReport}
import com.evernym.verity.protocol.protocols.relationship.v_1_0.RelationshipMsgFamily
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.protocol.protocols.updateConfigs.v_0_6.{ConfigResult, UpdateConfigsMsgFamily}
import com.evernym.verity.protocol.protocols.writeSchema.v_0_6.{WriteSchemaMsgFamily, StatusReport => WSStatusReport}
import com.typesafe.config.{Config, ConfigFactory}

import java.util.UUID


class RestIssuerSdkSpec
  extends VerityAppBaseSpec
    with SdkProvider {

  lazy val verityVAS = setupNewVerityApp(overriddenConfig = overriddenConfig)
  lazy val verityCAS = setupNewVerityApp()

  lazy val issuerRestSDK = setupIssuerRestSdk(verityVAS.platform)
  lazy val holderSDK = setupHolderSdk(verityCAS.platform)

  lazy val allVerityApps: List[HttpServer] = List(verityVAS, verityCAS)

  override def beforeAll(): Unit = {
    super.beforeAll()
    issuerRestSDK.fetchAgencyKey()
    issuerRestSDK.provisionVerityEdgeAgent()    //this sends a packed message (not REST api call)
    issuerRestSDK.registerWebhook()             //this sends a packed message (not REST api call)
  }

  var lastThreadId: Option[ThreadId] = None
  val firstConn = "connId1"
  var firstInvitation: Invitation = _

  "IssuerRestSdk" - {
    "when sent POST update-config 0.6 update message" - {
      "should be successful" in {
        val lastThreadId = Option(UUID.randomUUID().toString)
        val payload = s"""{"@type":"did:sov:123456789abcdefghi1234;spec/update-configs/0.6/update","@id":"${UUID.randomUUID.toString}","configs":[{"name": "name", "value":"env-name"},{"name": "logoUrl", "value":"env-logo-url" }]}"""
        val response = issuerRestSDK.sendPostReq(UpdateConfigsMsgFamily, payload, lastThreadId)
        response.status shouldBe Accepted
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[ConfigResult]
        receivedMsgParam.msg.configs.size shouldBe 2
      }
    }

    "when sent GET update-config 0.6 status message" - {
      "should be successful" in {
        val configResult = issuerRestSDK.sendGetStatusReq[ConfigResult](UpdateConfigsMsgFamily, lastThreadId)
        configResult.status shouldBe "OK"
        configResult.result.configs.size shouldBe 2
      }
    }

    "when sent POST issuer-setup 0.6 create message" - {
      "should be successful" in {
        val payload = s"""{"@type":"did:sov:123456789abcdefghi1234;spec/issuer-setup/0.6/create","@id":"${UUID.randomUUID.toString}"}"""
        val response = issuerRestSDK.sendPostReq(IssuerSetupMsgFamily, payload)
        response.status shouldBe Accepted

        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[PublicIdentifierCreated]
        receivedMsgParam.msg.identifier.did.nonEmpty shouldBe true
        receivedMsgParam.msg.identifier.verKey.nonEmpty shouldBe true
      }
    }

    "when sent POST write-schema 0.6 write message" - {
      "should be successful" in {
        val payload = s"""{"@type":"did:sov:123456789abcdefghi1234;spec/write-schema/0.6/write","@id":"${UUID.randomUUID.toString}","name":"schema-name","version":"1.0","attrNames":["firstName","lastName"]}"""
        val response = issuerRestSDK.sendPostReq(WriteSchemaMsgFamily, payload)
        response.status shouldBe Accepted
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[WSStatusReport]
        receivedMsgParam.msg.schemaId.nonEmpty shouldBe true
      }
    }

    "when sent POST relationship 1.0 create message" - {
      "should be successful" in {
        val receivedMsgParam = issuerRestSDK.createRelationship(firstConn)
        lastThreadId = receivedMsgParam.threadId
        receivedMsgParam.msg.did.nonEmpty shouldBe true
        receivedMsgParam.msg.verKey.nonEmpty shouldBe true
      }
    }

    "when sent POST relationship 1.0 connection-invitation message" - {
      "should be successful" in {
        val payload = s"""{"@type":"did:sov:123456789abcdefghi1234;spec/relationship/1.0/connection-invitation"}"""
        val response = issuerRestSDK.sendPostReq(RelationshipMsgFamily, payload, lastThreadId)
        response.status shouldBe Accepted
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[Invitation]
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
        holderSDK.sendConnReqForUnacceptedInvitation(firstConn, firstInvitation)
      }
    }
  }

  "IssuerSdk" - {
    "after user accepted invitation" - {
      "should receive notifications on webhook" in {
        issuerRestSDK.expectMsgOnWebhook[ConnReqReceived]
        issuerRestSDK.expectMsgOnWebhook[ConnResponseSent]
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[Complete]
        receivedMsgParam.msg.theirDid.isEmpty shouldBe false
      }
    }

    "when sent question-answer 1.0 ask-question message" - {
      "should be successful" in {
        val payload = s"""{"@type":"did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/questionanswer/1.0/ask-question", "text":"How are you?", "detail":"question-detail", "valid_responses":["I am fine","I am not fine"], "signature_required":false}"""
        val response = issuerRestSDK.sendPostReqForConn(firstConn, QuestionAnswerMsgFamily, payload)
        response.status shouldBe Accepted
      }
    }
  }

  "HolderSDK" - {
    "when tried to get newly un viewed messages" - {
      "should get 'question' message" in {
        val receivedMsgParam = holderSDK.expectMsgOnConn[Question](firstConn)
        lastThreadId = receivedMsgParam.threadId
        val question = receivedMsgParam.msg
        question.question_text shouldBe "How are you?"
      }
    }

    "when sent 'answer' message" - {
      "should be successful" in {
        val answer = Answer("I am fine", None, None)
        holderSDK.sendProtoMsgToTheirAgent(firstConn, answer, lastThreadId)
      }
    }
  }

  "IssuerSdk" - {
    "when tried to get newly un viewed messages" - {
      "should get 'answer' message" in {
        val receivedMsgParam = issuerRestSDK.expectMsgOnWebhook[AnswerGiven]
        receivedMsgParam.msg.answer shouldBe "I am fine"
      }
    }

    "when sent question-answer 1.0 get-status" - {
      "should be successful" in {
        val restOkResp = issuerRestSDK.sendGetStatusReqForConn[QAStatusReport](firstConn, QuestionAnswerMsgFamily, lastThreadId)
        restOkResp.status shouldBe "OK"
      }
    }
  }

  val overriddenConfig: Option[Config] = Option {
    ConfigFactory.parseString(
      """
         verity.rest-api.enabled = true
        """.stripMargin
    )
  }
}
