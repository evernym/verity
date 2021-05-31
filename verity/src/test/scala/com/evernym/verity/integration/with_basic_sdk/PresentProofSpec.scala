package com.evernym.verity.integration.with_basic_sdk

import com.evernym.verity.integration.base.VerityProviderBaseSpec
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.actor.agent.{Thread => MsgThread}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.{Issue, Offer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.{AcceptRequest, Sent}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.RequestPresentation
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.ProofAttribute
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}


class PresentProofSpec
  extends VerityProviderBaseSpec
  with SdkProvider {

  lazy val issuerVerityEnv = VerityEnvBuilder.default().build()
  lazy val verifierVerityEnv = VerityEnvBuilder.default().build()
  lazy val holderVerityEnv = VerityEnvBuilder.default().build()

  lazy val issuerSDK = setupIssuerSdk(issuerVerityEnv)
  lazy val verifierSDK = setupVerifierSdk(verifierVerityEnv)
  lazy val holderSDK = setupHolderSdk(holderVerityEnv, defaultSvcParam.ledgerTxnExecutor)

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
        issuerSDK.expectMsgOnWebhook[Sent]()
      }
    }
  }

  "HolderSDK" - {
    "when try to get un viewed messages" - {
      "should get 'offer-credential' (issue-credential 1.0) message" in {
        val receivedMsg = holderSDK.expectMsgFromConn[OfferCred](issuerHolderConn)
        offerCred = receivedMsg.msg
        lastReceivedThread = receivedMsg.threadOpt
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
        issuerSDK.expectMsgOnWebhook[AcceptRequest]()
      }
    }

    "when sent 'issue' (issue-credential 1.0) message" - {
      "should be successful" in {
        val issueMsg = Issue()
        issuerSDK.sendMsgForConn(issuerHolderConn, issueMsg, lastReceivedThread)
        issuerSDK.expectMsgOnWebhook[Sent]()
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
      val requestPresentation = receivedMsgParam.msg.requested_presentation
      requestPresentation.revealed_attrs.size shouldBe 2
      requestPresentation.unrevealed_attrs.size shouldBe 0
      requestPresentation.self_attested_attrs.size shouldBe 0
    }
  }
}