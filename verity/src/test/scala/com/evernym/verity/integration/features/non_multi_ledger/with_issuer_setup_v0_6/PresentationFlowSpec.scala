package com.evernym.verity.integration.features.non_multi_ledger.with_issuer_setup_v0_6

import com.evernym.verity.did.didcomm.v1.{Thread => MsgThread}
import com.evernym.verity.integration.base.endorser_svc_provider.MockEndorserServiceProvider
import com.evernym.verity.integration.base.endorser_svc_provider.MockEndorserUtil._
import com.evernym.verity.integration.base.sdk_provider.{HolderSdk, IssuerSdk, SdkProvider, VerifierSdk}
import com.evernym.verity.integration.base.verity_provider.VerityEnv
import com.evernym.verity.integration.base.{CAS, VAS, VerityProviderBaseSpec}
import com.evernym.verity.integration.features.non_multi_ledger._
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Ctl.{Issue, Offer}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Sig.{AcceptRequest, Sent}
import com.evernym.verity.protocol.protocols.issuersetup.v_0_6.PublicIdentifier
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.RequestPresentation
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.{ProofAttribute, ProofPredicate, RestrictionsV1}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Sig.PresentationResult
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.VerificationResults.ProofValidated
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.{v_0_6 => writeCredDef0_6}
import com.evernym.verity.protocol.protocols.writeSchema.{v_0_6 => writeSchema0_6}
import com.evernym.verity.vdr.DID_PREFIX
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.concurrent.Await


class PresentationFlowSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  lazy val issuerVerityEnv: VerityEnv = VerityEnvBuilder().withConfig(OVERRIDDEN_CONFIG).build(VAS)
  lazy val verifierVerityEnv: VerityEnv = VerityEnvBuilder().withConfig(OVERRIDDEN_CONFIG).build(VAS)
  lazy val holderVerityEnv: VerityEnv = VerityEnvBuilder().withConfig(OVERRIDDEN_CONFIG).build(CAS)

  lazy val issuerSDK: IssuerSdk = setupIssuerSdk(issuerVerityEnv, executionContext)
  lazy val verifierSDK: VerifierSdk = setupVerifierSdk(verifierVerityEnv, executionContext)
  lazy val holderSDK: HolderSdk = setupHolderSdk(holderVerityEnv, executionContext,
    defaultSvcParam.ledgerTxnExecutor, defaultSvcParam.vdrTools, isMultiLedgerSupported = false)

  lazy val endorserSvcProvider: MockEndorserServiceProvider = MockEndorserServiceProvider(issuerVerityEnv)

  val issuerHolderConn = "connId1"
  val verifierHolderConn = "connId2"

  var pubIdentifier: PublicIdentifier = _
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

    Await.result(endorserSvcProvider.publishEndorserActivatedEvent(activeEndorserDid, INDY_LEDGER_PREFIX), 5.seconds)

    pubIdentifier = setupIssuer_v0_6(issuerSDK)
    pubIdentifier.did.startsWith(DID_PREFIX) shouldBe false
    schemaId = writeSchema_v0_6(issuerSDK, writeSchema0_6.Write("name", "1.0", Seq("name", "age")))
    schemaId.startsWith(DID_PREFIX) shouldBe false
    credDefId = writeCredDef_v0_6(issuerSDK, writeCredDef0_6.Write("name", schemaId, None, None))
    credDefId.startsWith(DID_PREFIX) shouldBe false

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
        checkOfferSentForNonFQIdentifiers(receivedMsg.msg)
        issuerSDK.checkMsgOrders(receivedMsg.threadOpt, 0, Map.empty)
      }
    }
  }

  "HolderSDK" - {
    "when try to get un viewed messages" - {
      "should get 'offer-credential' (issue-credential 1.0) message" in {
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
        checkCredSentForNonFQIdentifiers(receivedMsg.msg)
        issuerSDK.checkMsgOrders(receivedMsg.threadOpt, 1, Map(issuerHolderConn -> 0))
      }
    }
  }

  "HolderSDK" - {
    "when try to get un viewed messages" - {
      "should get 'issue-credential' (issue-credential 1.0) message" in {
        val receivedMsg = holderSDK.downloadMsg[IssueCred](issuerHolderConn)
        holderSDK.storeCred(receivedMsg.msg, lastReceivedThread)
      }
    }
  }

  "Verifier and Holder" - {
    "when tested various proof presentation" - {
      "should be successful" in {

        val restrictionCombinations = Seq(
          //restriction without any identifiers
          List(
            RestrictionsV1(
              schema_id = None,
              schema_issuer_did = None,
              schema_name = None,
              schema_version = None,
              issuer_did = None,
              cred_def_id = None
            )
          ),
          //restriction with only issuer_did attribute
          List(
            RestrictionsV1(
              schema_id = None,
              schema_issuer_did = None,
              schema_name = None,
              schema_version = None,
              issuer_did = Option(pubIdentifier.did),
              cred_def_id = None
            )
          ),
          //restriction with only schema_id attribute
          List(
            RestrictionsV1(
              schema_id = Option(schemaId),
              schema_issuer_did = None,
              schema_name = None,
              schema_version = None,
              issuer_did = None,
              cred_def_id = None
            )
          ),
          //restriction with only cred_def_id attribute
          List(
            RestrictionsV1(
              schema_id = None,
              schema_issuer_did = None,
              schema_name = None,
              schema_version = None,
              issuer_did = None,
              cred_def_id = Option(credDefId)
            )
          ),
          //restriction with multiple attributes
          List(
            RestrictionsV1(
              schema_id = Option(schemaId),
              schema_issuer_did = None,
              schema_name = None,
              schema_version = None,
              issuer_did = Option(pubIdentifier.did),
              cred_def_id = Option(credDefId)
            )
          )
        )
        restrictionCombinations.foreach { restrictions =>
          //tests the presentation flow with given `restrictions`
          performProofPresentation(
            Option(List(
              ProofAttribute(
                None,
                Option(List("name")),
                Option(restrictions),
                None,
                self_attest_allowed = false)
            )),
            Option(List(
              ProofPredicate(
                "age",
                ">=",
                20,
                Option(restrictions),
                None)
            ))
          )
        }
      }
    }
  }

  def performProofPresentation(proofAttrs: Option[List[ProofAttribute]],
                               proofPredicates: Option[List[ProofPredicate]]): Unit = {
    val msg = Request(
      "name-age",
      proofAttrs,
      proofPredicates,
      None
    )
    verifierSDK.sendMsgForConn(verifierHolderConn, msg)

    val receivedMsg = holderSDK.downloadMsg[RequestPresentation](verifierHolderConn)
    lastReceivedThread = receivedMsg.threadOpt
    proofReq = receivedMsg.msg

    holderSDK.acceptProofReq(verifierHolderConn, proofReq, Map.empty, lastReceivedThread)
    val receivedMsgParam = verifierSDK.expectMsgOnWebhook[PresentationResult]()
    checkPresentationForNonFQIdentifiers(receivedMsgParam.msg)
    receivedMsgParam.msg.verification_result shouldBe ProofValidated
    val requestPresentation = receivedMsgParam.msg.requested_presentation
    requestPresentation.revealed_attrs.size shouldBe proofAttrs.getOrElse(List.empty).size
    requestPresentation.predicates.size shouldBe proofPredicates.getOrElse(List.empty).size
    requestPresentation.unrevealed_attrs.size shouldBe 0
    requestPresentation.self_attested_attrs.size shouldBe 0
  }

  val OVERRIDDEN_CONFIG: Config =
    ConfigFactory.parseString(
      """
         verity.vdr.multi-ledger-support-enabled = false
        """.stripMargin
    )
}