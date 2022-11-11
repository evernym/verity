package com.evernym.verity.integration.features.push_notification.verity2

import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.{ComMethod, UpdateComMethodReqMsg, UpdateConfigReqMsg}
import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.sdk_provider.msg_listener.JsonMsgListener
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider, VerifierSdk}
import com.evernym.verity.integration.base.{CAS, PortProvider, VAS, VerityProviderBaseSpec}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.{Issue, Offer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.{AcceptRequest, Sent}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.RequestPresentation
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProofAttribute
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.VerificationResults.ProofValidated
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Ctl.AskQuestion
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Msg.{Answer, Question}
import com.evernym.verity.protocol.protocols.questionAnswer.v_1_0.Signal.AnswerGiven
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.testkit.TestSponsor
import com.evernym.verity.util.TimeUtil
import com.typesafe.config.ConfigFactory
import org.json.JSONObject

import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.Await


class SponsorPushNotifsSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  val system: ActorSystem = ActorSystemVanilla(UUID.randomUUID().toString)

  val testSponsor = new TestSponsor(seed = "000000000000000000000000Sponsor1", futExecutionContext = executionContext, system)

  var issuerSDK: IssuerSdk = _
  var verifierSDK: VerifierSdk = _
  var holderSDK: HolderSdk = _

  val issuerHolderConn = "connId1"
  val verifierHolderConn = "connId2"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _
  var offerCred: OfferCred = _

  var proofReq: RequestPresentation = _

  var lastReceivedThread: Option[MsgThread] = None

  override def beforeAll(): Unit = {
    super.beforeAll()

    val issuerVerityEnv = VerityEnvBuilder().buildAsync(VAS)
    val verifierVerityEnv = VerityEnvBuilder().buildAsync(VAS)
    val holderVerityEnv = VerityEnvBuilder().withConfig(CAS_CONFIG).buildAsync(CAS)

    val issuerSDKFut = setupIssuerSdkAsync(issuerVerityEnv, executionContext)
    val verifierSDKFut = setupVerifierSdkAsync(verifierVerityEnv, executionContext)
    val holderSDKFut = setupHolderSdkAsync(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor, defaultSvcParam.vdrTools, executionContext)

    issuerSDK   = Await.result(issuerSDKFut, SDK_BUILD_TIMEOUT)
    verifierSDK = Await.result(verifierSDKFut, SDK_BUILD_TIMEOUT)
    holderSDK   = Await.result(holderSDKFut, SDK_BUILD_TIMEOUT)

    provisionEdgeAgent(issuerSDK)   //sets 'issuer-name' in the agent configuration
    //change the issuer's name if needed
    issuerSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "config-issuer-name"))))

    provisionEdgeAgent(verifierSDK) //sets 'issuer-name' in the agent configuration
    //change the verifier's name if needed
    verifierSDK.sendUpdateConfig(UpdateConfigReqMsg(Set(ConfigDetail("name", "config-verifier-name"))))

    val provToken = holderSDK.buildProvToken(
      "sponseeId",
      "test-sponsor",
      UUID.randomUUID().toString,
      TimeUtil.nowDateString,
      testSponsor
    )
    holderSDK.provisionVerityCloudAgent(provToken)
    holderSDK.registerWebhook(UpdateComMethodReqMsg(
      ComMethod("id", 1, s"FCM:localhost:${pushNotifListener.port}/webhook", None, None)
    ))

    setupIssuer_v0_6(issuerSDK)
    schemaId = writeSchema_v0_6(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef_v0_6(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))

    establishConnection(issuerHolderConn, issuerSDK, holderSDK)         //TODO: you can provide a label in the 2nd parameter if you want to
    checkHolderPushNotif { pushNotifJson =>
      val data = pushNotifJson.getJSONObject("data")
      val msgType = data.getString("msgType")
      msgType shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response"
    }
    establishConnection(verifierHolderConn, verifierSDK, holderSDK)     //TODO: you can provide a label in the 2nd parameter if you want to
    checkHolderPushNotif { pushNotifJson =>
      val data = pushNotifJson.getJSONObject("data")
      val msgType = data.getString("msgType")
      msgType shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/connections/1.0/response"
    }
  }

  "IssuerSDK" - {
    "when tried to send 'ask-question' (questionanswer 1.0) message" - {
      "should be successful" in {
        issuerSDK.sendMsgForConn(issuerHolderConn, AskQuestion("How are you?", Option("detail"), Vector("I am fine", "I am not fine"), signature_required = false, None))
      }
    }
  }

  "HolderSDK" - {
    "when tried to get newly un viewed messages" - {
      "should get 'question' (questionanswer 1.0) message" in {

        checkHolderPushNotif { pushNotifJson =>
          val data = pushNotifJson.getJSONObject("data")
          val msgType = data.getString("msgType")
          val pushNotifMsgText = data.getString("pushNotifMsgText")
          msgType shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/questionanswer/1.0/question"
          pushNotifMsgText shouldBe "Sponsor: Your credit union is asking you a question"
        }

        val receivedMsg = holderSDK.downloadMsg[Question](issuerHolderConn)
        lastReceivedMsgThread = receivedMsg.threadOpt
        val question = receivedMsg.msg
        question.question_text shouldBe "How are you?"
      }
    }

    "when tried to respond with 'answer' (questionanswer 1.0) message" - {
      "should be successful" in {
        val answer = Answer("I am fine", None, None)
        holderSDK.sendProtoMsgToTheirAgent(issuerHolderConn, answer, lastReceivedMsgThread)
      }
    }
  }

  "IssuerSDK" - {
    "should receive 'answer-given' (questionanswer 1.0) message" in {
      val receivedMsg = issuerSDK.expectMsgOnWebhook[AnswerGiven]()
      receivedMsg.msg.answer shouldBe "I am fine"
    }
  }

  "IssuerSDK" - {
    "sends 'offer' (issue-credential 1.0) message" - {
      "should be successful" in {
        val offerMsg = Offer(
          credDefId,
          Map("name" -> "Alice", "age" -> "20")
        )
        issuerSDK.sendMsgForConn(issuerHolderConn, offerMsg)
        val receivedMsg = issuerSDK.expectMsgOnWebhook[Sent]()
        issuerSDK.checkMsgOrders(receivedMsg.threadOpt, 0, Map.empty)
      }
    }
  }

  "HolderSDK" - {
    "when try to get un viewed messages" - {
      "should get 'offer-credential' (issue-credential 1.0) message" in {
        checkHolderPushNotif { pushNotifJson =>
          val data = pushNotifJson.getJSONObject("data")
          val msgType = data.getString("msgType")
          val pushNotifMsgText = data.getString("pushNotifMsgText")
          msgType shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/offer-credential"
          pushNotifMsgText shouldBe "Sponsor: Your credit union has sent you a credential offer"
        }

        val receivedMsg = holderSDK.downloadMsg[OfferCred](issuerHolderConn)
        offerCred = receivedMsg.msg
        lastReceivedThread = receivedMsg.threadOpt
        holderSDK.checkMsgOrders(lastReceivedThread, 0, Map.empty)
      }
    }

    "when sent 'request-credential' (issue-credential 1.0) message" - {
      "should be successful" in {
        holderSDK.sendCredRequest(issuerHolderConn, offerCred, lastReceivedThread)
      }
    }
  }

  "IssuerSDK" - {
    "when waiting for message on webhook" - {
      "should get 'accept-request' (issue-credential 1.0)" in {
        val receivedMsg = issuerSDK.expectMsgOnWebhook[AcceptRequest]()
        issuerSDK.checkMsgOrders(receivedMsg.threadOpt, 0, Map(issuerHolderConn -> 0))
      }
    }

    "when sent 'issue' (issue-credential 1.0) message" - {
      "should be successful" in {
        val issueMsg = Issue()
        issuerSDK.sendMsgForConn(issuerHolderConn, issueMsg, lastReceivedThread)
        val receivedMsg = issuerSDK.expectMsgOnWebhook[Sent]()
        issuerSDK.checkMsgOrders(receivedMsg.threadOpt, 1, Map(issuerHolderConn -> 0))
      }
    }
  }

  "HolderSDK" - {
    "when try to get un viewed messages" - {
      "should get 'issue-credential' (issue-credential 1.0) message" in {
        checkHolderPushNotif { pushNotifJson =>
          val data = pushNotifJson.getJSONObject("data")
          val msgType = data.getString("msgType")
          val pushNotifMsgText = data.getString("pushNotifMsgText")
          msgType shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/issue-credential"
          pushNotifMsgText shouldBe "Sponsor: Your credit union has sent you a credential"
        }

        val receivedMsg = holderSDK.downloadMsg[IssueCred](issuerHolderConn)
        holderSDK.storeCred(receivedMsg.msg, lastReceivedThread)
      }
    }
  }

  "VerifierSDK" - {
    "sent 'request' (present-proof 1.0) message" - {
      "should be successful" in {
        val msg = Request("name-age",
          Option(List(
            ProofAttribute(
              None,
              Option(List("name", "age")),
              None,
              None,
              self_attest_allowed = false)
          )),
          None,
          None
        )
        verifierSDK.sendMsgForConn(verifierHolderConn, msg)
      }
    }
  }

  "HolderSDK" - {
    "when tried to get un viewed messages" - {
      "should get 'request-presentation' (present-proof 1.0) message" in {
        checkHolderPushNotif { pushNotifJson =>
          val data = pushNotifJson.getJSONObject("data")
          val msgType = data.getString("msgType")
          val pushNotifMsgText = data.getString("pushNotifMsgText")
          msgType shouldBe "did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/present-proof/1.0/request-presentation"
          pushNotifMsgText shouldBe "Sponsor: Your credit union has sent you a proof request"
        }

        val receivedMsg = holderSDK.downloadMsg[RequestPresentation](verifierHolderConn)
        lastReceivedThread = receivedMsg.threadOpt
        proofReq = receivedMsg.msg
      }
    }

    "when tried to send 'presentation' (present-proof 1.0) message" - {
      "should be successful" in {
        holderSDK.acceptProofReq(verifierHolderConn, proofReq, Map.empty, lastReceivedThread)
      }
    }
  }

  "VerifierSDK" - {
    "should receive 'presentation-result' (present-proof 1.0) message on webhook" in {
      val receivedMsgParam = verifierSDK.expectMsgOnWebhook[PresentationResult]()
      receivedMsgParam.msg.verification_result shouldBe ProofValidated
      val requestPresentation = receivedMsgParam.msg.requested_presentation
      requestPresentation.revealed_attrs.size shouldBe 2
      requestPresentation.unrevealed_attrs.size shouldBe 0
      requestPresentation.self_attested_attrs.size shouldBe 0
    }
  }

  lazy val pushNotifListener = new JsonMsgListener(PortProvider.getFreePort, None)(holderSDK.system)

  var lastReceivedMsgThread: Option[MsgThread] = None

  private def checkHolderPushNotif(check: JSONObject => Unit): Unit = {
    val msg = pushNotifListener.expectMsg(20.seconds)
    val pushNotif = new JSONObject(msg)
    check(pushNotif)
  }

  lazy val CAS_CONFIG = ConfigFactory.parseString(
    s"""
       |
       |verity.provisioning {
       |  sponsor-required = true
       |  sponsors: [
       |    {
       |      name: "test-sponsor"
       |      id: "test-sponsor"
       |      endpoint = ""
       |      keys: [{"verKey": "${testSponsor.verKey}"}]
       |      active: true
       |      push-msg-overrides: {
       |        "general-new-msg-body-template" = "Sponsor: Your credit union has sent you #{msgType}"
       |        "questionanswer_1.0_question-new-msg-body-template" = "Sponsor: Your credit union is asking you #{msgType}"
       |      }
       |    }
       |  ]
       |}
       |
       |verity.services.push-notif-service {
       | enabled = true
       | general-new-msg-body-template = "Your credit union has sent you #{msgType}"
       | questionanswer_1.0_question-new-msg-body-template = "Your credit union is asking you #{msgType}"
       | fcm {
       |   provider = "com.evernym.verity.testkit.mock.pushnotif.MockFirebasePusher"
       |   send-messages-to-endpoint = true
       | }
       |}
       |""".stripMargin)
}
