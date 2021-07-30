package com.evernym.verity.integration.with_basic_sdk.data_retention.expire_after_ternminal_state

import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.with_basic_sdk.data_retention.DataRetentionBaseSpec
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.{Issue, Offer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.{AcceptRequest, Sent}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.RequestPresentation
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProofAttribute
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.VerificationResults.ProofValidated
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.typesafe.config.ConfigFactory


class PresentProofSpec
  extends VerityProviderBaseSpec
    with DataRetentionBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv =
    VerityEnvBuilder
      .default()
      .withServiceParam(buildSvcParam)
      .withConfig(DATA_RETENTION_CONFIG)
      .build(VAS)

  lazy val verifierVerityEnv =
    VerityEnvBuilder.
      default()
      .withServiceParam(buildSvcParam)
      .withConfig(DATA_RETENTION_CONFIG)
      .build(VAS)

  lazy val holderVerityEnv = VerityEnvBuilder.default().build(CAS)

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv)
  lazy val verifierSDK = setupVerifierSdk(verifierVerityEnv)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, ledgerTxnExecutor)

  val issuerHolderConn = "connId1"
  val verifierHolderConn = "connId2"

  var schemaId: SchemaId = _
  var credDefId: CredDefId = _
  var offerCred: OfferCred = _

  var proofReq: RequestPresentation = _

  var lastReceivedThread: Option[MsgThread] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    provisionEdgeAgent(issuerSDK)
    provisionEdgeAgent(verifierSDK)
    provisionCloudAgent(holderSDK)

    setupIssuer(issuerSDK)
    schemaId = writeSchema(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    credDefId = writeCredDef(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))

    establishConnection(issuerHolderConn, issuerSDK, holderSDK)
    establishConnection(verifierHolderConn, verifierSDK, holderSDK)
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
        issuerVerityEnv.checkBlobObjectCount("3d", 1)
      }
    }
  }

  "HolderSDK" - {
    "when try to get un viewed messages" - {
      "should get 'offer-credential' (issue-credential 1.0) message" in {
        val receivedMsg = holderSDK.expectMsgFromConn[OfferCred](issuerHolderConn)
        offerCred = receivedMsg.msg
        lastReceivedThread = receivedMsg.threadOpt
        holderSDK.checkMsgOrders(lastReceivedThread, 0, Map.empty)
      }
    }

    "when sent 'request-credential' (issue-credential 1.0) message" - {
      "should be successful" in {
        holderSDK.sendCredRequest(issuerHolderConn, credDefId, offerCred, lastReceivedThread)
      }
    }
  }

  "IssuerSDK" - {
    "when waiting for message on webhook" - {
      "should get 'accept-request' (issue-credential 1.0)" in {
        val receivedMsg = issuerSDK.expectMsgOnWebhook[AcceptRequest]()
        issuerSDK.checkMsgOrders(receivedMsg.threadOpt, 0, Map(issuerHolderConn -> 0))
        issuerVerityEnv.checkBlobObjectCount("3d", 2)
      }
    }

    "when sent 'issue' (issue-credential 1.0) message" - {
      "should be successful" in {
        val issueMsg = Issue()
        issuerSDK.sendMsgForConn(issuerHolderConn, issueMsg, lastReceivedThread)
        val receivedMsg = issuerSDK.expectMsgOnWebhook[Sent]()
        issuerSDK.checkMsgOrders(receivedMsg.threadOpt, 1, Map(issuerHolderConn -> 0))
        issuerVerityEnv.checkBlobObjectCount("3d", 0)
      }
    }
  }

  "HolderSDK" - {
    "when try to get un viewed messages" - {
      "should get 'issue-credential' (issue-credential 1.0) message" in {
        val receivedMsg = holderSDK.expectMsgFromConn[IssueCred](issuerHolderConn)
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
        verifierVerityEnv.checkBlobObjectCount("3d", 1)
      }
    }
  }

  "HolderSDK" - {
    "when tried to get un viewed messages" - {
      "should get 'request-presentation' (present-proof 1.0) message" in {
        val receivedMsg = holderSDK.expectMsgFromConn[RequestPresentation](verifierHolderConn)
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
      verifierVerityEnv.checkBlobObjectCount("3d", 0)
    }
  }

  val DATA_RETENTION_CONFIG = ConfigFactory.parseString {
    """
      |verity {
      |  retention-policy {
      |    protocol-state {
      |      default {
      |        undefined-fallback {
      |          expire-after-days = 3 day
      |          expire-after-terminal-state = true
      |        }
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

}
