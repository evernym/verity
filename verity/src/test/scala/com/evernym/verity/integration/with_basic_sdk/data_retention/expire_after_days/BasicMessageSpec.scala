package com.evernym.verity.integration.with_basic_sdk.data_retention.expire_after_days

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.{Thread => MsgThread}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.agentmsg.msgfamily.configs.UpdateConfigReqMsg
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.with_basic_sdk.data_retention.DataRetentionBaseSpec
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Ctl.SendMessage
import com.evernym.verity.protocol.protocols.basicMessage.v_1_0.Msg.Message
import com.evernym.verity.protocol.protocols.relationship.v_1_0.Signal.Invitation
import com.evernym.verity.util.TestExecutionContextProvider
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext


class BasicMessageSpec
  extends VerityProviderBaseSpec
    with DataRetentionBaseSpec
    with SdkProvider {

  lazy val ecp = TestExecutionContextProvider.ecp
  lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  lazy val issuerVerityEnv =
    VerityEnvBuilder
      .default()
      .withServiceParam(buildSvcParam)
      .withConfig(DATA_RETENTION_CONFIG)
      .build(VAS)

  lazy val holderVerityEnv = VerityEnvBuilder.default().build(CAS)

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv, executionContext, ecp.walletFutureExecutionContext)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, ledgerTxnExecutor, executionContext, ecp.walletFutureExecutionContext)

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
        issuerVerityEnv.checkBlobObjectCount("4d", 1)
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
      issuerVerityEnv.checkBlobObjectCount("4d", 2)
    }
  }

  val DATA_RETENTION_CONFIG = ConfigFactory.parseString {
    """
      |verity {
      |  retention-policy {
      |    protocol-state {
      |      default {
      |       undefined-fallback {
      |         expire-after-days = 4 day
      |         expire-after-terminal-state = false
      |       }
      |      }
      |    }
      |  }
      |  blob-store {
      |   storage-service = "com.evernym.verity.testkit.mock.blob_store.MockBlobStore"
      |
      |   # The bucket name will contain <env> depending on which environment is used -> "verity-<env>-blob-storage"
      |   bucket-name = "local-blob-store"
      |   # Path to StorageAPI class to be used. Currently there is a LeveldbAPI and AlpakkaS3API
      |  }
      |}
      |""".stripMargin
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext

  override def executionContextProvider: ExecutionContextProvider = ecp
}
