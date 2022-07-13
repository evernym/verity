package com.evernym.integrationtests.e2e.vdrtools

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import com.evernym.integrationtests.e2e.util.TestExecutionContextProvider
import com.evernym.vdrtools.IndyException
import com.evernym.vdrtools.anoncreds.Anoncreds
import com.evernym.vdrtools.crypto.Crypto
import com.evernym.vdrtools.did.{Did, DidJSONParameters}
import com.evernym.vdrtools.wallet.Wallet
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.actor.wallet.{CreateNewKey, CredCreated, CredDefCreated, CredForProofReqCreated, CredOfferCreated, CredReqCreated, CredStored, MasterSecretCreated, NewKeyCreated, SignedMsg}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.did.didcomm.v1.decorators.AttachmentDescriptor.buildAttachment
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.{CredIssued, CredOffered, CredRequested, IssueCredential}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredential.{buildCredPreview, extractCredOfferJson, extractCredReqJson, toAttachmentObject}
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Msg.{IssueCred, OfferCred, RequestCred}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Msg.RequestPresentation
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.PresentProof.{credentialsToUse, extractAttachment}
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.{AttIds, AvailableCredentials, Msg, ProofAttribute, ProofRequestUtil}
import com.evernym.verity.testkit.util.{LedgerUtil => LegacyLedgerUtil}
import com.evernym.verity.testkit.{BasicSpec, LedgerClient}
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util.JsonUtil.seqToJson
import com.evernym.verity.vault.WalletUtil.generateWalletParamSync
import com.evernym.verity.vault.WalletDoesNotExist
import com.evernym.verity.vault.operation_executor.FutureConverter
import com.evernym.verity.vdr.service.{VDRToolsConfig, VDRToolsFactory, VdrToolsBuilderImpl}
import com.evernym.verity.vdr.{CredDef, FqDID, LedgerStatus, PreparedTxn, Schema, SubmittedTxn, VDRActorAdapter, VDRAdapter, VDRUtil}
import com.evernym.verity.vdrtools.Libraries
import com.evernym.verity.vdrtools.wallet.LibIndyWalletProvider
import com.typesafe.config.{Config, ConfigFactory}
import org.json.JSONObject
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.PatienceConfiguration.{Interval, Timeout}

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

//purpose of this spec is to test VdrTools api for Indy based ledgers
class IndyVdrSpec
  extends BasicSpec
    with BeforeAndAfterAll
    with FutureConverter {

  override def beforeAll(): Unit = {
    Libraries.initialize(appConfig)
  }


  implicit val actorSystem: ActorSystem = ActorSystemVanilla("test")
  implicit val ec: ExecutionContext = TestExecutionContextProvider.ecp.futureExecutionContext

  var trusteeWallet: Wallet = createOrOpenWallet("trusteeWallet")
  var trusteeKey: NewKeyCreated = createNewKey(trusteeWallet, CreateNewKey(seed = Option("000000000000000000000000Trustee1")))
  var trusteeFqDid: FqDID = VDRUtil.toFqDID(trusteeKey.did, UNQUALIFIED_LEDGER_PREFIX, ledgerPrefixMapping)

  var issuerWallet: Wallet = createOrOpenWallet("issuerWallet")
  var issuerKey: NewKeyCreated = createNewKey(issuerWallet, CreateNewKey())
  var issuerFqDid: FqDID = VDRUtil.toFqDID(issuerKey.did, UNQUALIFIED_LEDGER_PREFIX, ledgerPrefixMapping)

  var verifierWallet: Wallet = createOrOpenWallet("verifierWallet")
  var verifierKey: NewKeyCreated = createNewKey(verifierWallet, CreateNewKey())
  var verifierFqDid: FqDID = VDRUtil.toFqDID(verifierKey.did, UNQUALIFIED_LEDGER_PREFIX, ledgerPrefixMapping)

  val holderWallet: Wallet = createOrOpenWallet("holderWallet")
  var holderKey: NewKeyCreated = createNewKey(holderWallet, CreateNewKey())
  var holderFqDid: FqDID = VDRUtil.toFqDID(holderKey.did, UNQUALIFIED_LEDGER_PREFIX, ledgerPrefixMapping)

  val holderMasterSecretId: String = UUID.randomUUID().toString
  runAsSync(Anoncreds.proverCreateMasterSecret(holderWallet, holderMasterSecretId).map(ms => MasterSecretCreated(ms)))

  val legacyLedgerUtil: LegacyLedgerUtil = LedgerClient.buildLedgerUtil(
    config = new TestAppConfig(newConfig = Option(vdrConfig), clearValidators = true),
    ec = ec,
    submitterDID = Option(trusteeKey.did),
    submitterKeySeed = Option("000000000000000000000000Trustee1"),
    genesisTxnPath = Option("target/genesis.txt")
  )

  "VDRToolsAdapter" - {

    "when called ping api" - {
      "should be successful" in {
        val pingResult = runAsSync(vdrAdapter.ping(List(INDY_NAMESPACE)))
        pingResult.status shouldBe Map(INDY_NAMESPACE -> LedgerStatus(reachable = true))
      }
    }

    "when tried to write schema before issuer DID is on the ledger" - {
      "should fail" in {
        val preparedTxn = runAsSync {
          val schemaCreated = Anoncreds.issuerCreateSchema(
            issuerFqDid,
            "employment",
            "1.0",
            seqToJson(List("name", "company"))
          ).get()

          vdrAdapter.prepareSchemaTxn(
            schemaCreated.getSchemaJson,
            VDRUtil.toFqSchemaId_v0(schemaCreated.getSchemaId, Option(issuerFqDid), Option(UNQUALIFIED_LEDGER_PREFIX)),
            issuerFqDid,
            None
          )
        }

        val ex = intercept[IndyException] {
          signAndSubmitTxn(issuerWallet, issuerKey.verKey, preparedTxn)
        }
        ex.getSdkMessage.contains(s"client request invalid: could not authenticate, verkey for ${issuerKey.did} cannot be found")
      }
    }

    "when tried to bootstrap issuer DID" - {
      "should be successful" in {
        bootstrapIssuerDIDViaVDRTools(issuerKey.did, issuerKey.verKey, "ENDORSER")
        //bootstrapIssuerDIDLegacy(issuerKey.did, issuerKey.verKey, "ENDORSER")
        eventually(Timeout(10.seconds), Interval(Duration("20 seconds"))) {
          legacyLedgerUtil.checkDidOnLedger(issuerKey.did, issuerKey.verKey, "ENDORSER")
        }
      }
    }

    "when tried to write schema" - {
      "should be successful" in {
        val preparedTxn = runAsSync {
          val result = Anoncreds.issuerCreateSchema(
            issuerFqDid,
            "employment",
            "1.0",
            seqToJson(List("name", "company"))
          ).get()
          schemaCreated = Schema(result.getSchemaId, result.getSchemaJson)
          schemaCreated.fqId shouldBe s"$UNQUALIFIED_LEDGER_PREFIX:${issuerKey.did}/anoncreds/v0/SCHEMA/employment/1.0"
          vdrAdapter.prepareSchemaTxn(
            schemaCreated.json,
            VDRUtil.toFqSchemaId_v0(schemaCreated.fqId, Option(issuerFqDid), Option(UNQUALIFIED_LEDGER_PREFIX)),
            issuerFqDid,
            None
          )
        }

        val submittedTxn = signAndSubmitTxn(issuerWallet, issuerKey.verKey, preparedTxn)

        val response = new JSONObject(submittedTxn.response)
        response.getString("op") shouldBe "REPLY"
        val result = response.getJSONObject("result")
        result.getInt("ver") shouldBe 1
        val reqSignature = result.getJSONObject("reqSignature")
        reqSignature.getString("type") shouldBe "ED25519"   //TODO: is this ok?
        val txnMetadata = result.getJSONObject("txnMetadata")
        txnMetadata.getInt("seqNo") > 0 shouldBe true

        val txn = result.getJSONObject("txn")
        txn.getInt("type") shouldBe 101
        txn.getInt("protocolVersion") shouldBe 2
        val txnInnerMetadata = txn.getJSONObject("metadata")

        val txnData = txn.getJSONObject("data").getJSONObject("data")
        txnData.getString("version") shouldBe "1.0"
        txnData.getString("name") shouldBe "employment"
        //txnData.getJSONArray("attr_names").iterator().asScala.toSeq.map(_.toString).sorted shouldBe List("name", "company")
      }
    }

    "when tried to write cred def" - {
      "should be successful" in {
        val schema = runAsSync(vdrAdapter.resolveSchema(schemaCreated.fqId))
        schema.fqId shouldBe s"$UNQUALIFIED_LEDGER_PREFIX:${issuerKey.did}/anoncreds/v0/SCHEMA/employment/1.0"
        val schemaJSONObj = new JSONObject(schema.json)
        val seqNo = schemaJSONObj.getNumber("seqNo")
        val preparedTxn = runAsSync {
          val configJson = "{}"
          val result = runAsSync(
            Anoncreds.issuerCreateAndStoreCredentialDef(
              issuerWallet,
              issuerFqDid,
              schema.json,
              "latest",
             "CL",
             configJson
            ).map(r => CredDefCreated(r.getCredDefId, r.getCredDefJson)))
          credDefCreated = CredDef(result.credDefId, schema.fqId, result.credDefJson)
          credDefCreated.fqId shouldBe s"$UNQUALIFIED_LEDGER_PREFIX:${issuerKey.did}/anoncreds/v0/CLAIM_DEF/$seqNo/latest"
          vdrAdapter.prepareCredDefTxn(
            credDefCreated.json,
            VDRUtil.toFqSchemaId_v0(credDefCreated.fqId, Option(issuerFqDid), Option(UNQUALIFIED_LEDGER_PREFIX)),
            issuerFqDid,
            None
          )
        }

        val submittedTxn = signAndSubmitTxn(issuerWallet, issuerKey.verKey, preparedTxn)
        val response = new JSONObject(submittedTxn.response)
        response.getString("op") shouldBe "REPLY"
        val result = response.getJSONObject("result")
        result.getInt("ver") shouldBe 1
        val reqSignature = result.getJSONObject("reqSignature")
        reqSignature.getString("type") shouldBe "ED25519"   //TODO: is this ok?
        val txnMetadata = result.getJSONObject("txnMetadata")
        txnMetadata.getInt("seqNo") > 0 shouldBe true

        val txn = result.getJSONObject("txn")
        txn.getInt("type") shouldBe 102
        txn.getInt("protocolVersion") shouldBe 2
        val txnInnerMetadata = txn.getJSONObject("metadata")

        val txnData = txn.getJSONObject("data").getJSONObject("data")
        val primary = txnData.getJSONObject("primary")
        val r = primary.getJSONObject("r")
        r.getString("master_secret")
        val s = primary.getString("s")
        val z = primary.getString("z")
        val n = primary.getString("n")
        val rctxt = primary.getString("rctxt")
      }
    }

    "when tried to issue a credential" - {
      "should be successful" in {
        //issuer creates cred offer
        val credOfferCreated = runAsSync(Anoncreds.issuerCreateCredentialOffer(issuerWallet, credDefCreated.fqId).map(co => CredOfferCreated(co)))
        val offerCred = buildOfferCred(credOfferCreated)

        //holder creates cred request
        val credReqCreated = runAsSync(Anoncreds.proverCreateCredentialReq(
          holderWallet, holderKey.did, credOfferCreated.offer, credDefCreated.json, holderMasterSecretId)
          .map(r => CredReqCreated(r.getCredentialRequestJson, r.getCredentialRequestMetadataJson)))
        val requestCred = buildRequestCred(credReqCreated)

        //issuer issues credential
        val credOfferJson = extractCredOfferJson(offerCred)
        val credReqJson = extractCredReqJson(requestCred)
        val credValuesJson = IssueCredential.buildCredValueJson(offerCred.credential_preview)
        val credCreated = runAsSync(Anoncreds.issuerCreateCredential(issuerWallet,
          credOfferJson, credReqJson, credValuesJson, null, -1)
          .map(r => CredCreated(r.getCredentialJson)))
        val issuedCred = buildCredIssued(credCreated)

        //holder stores the issued credential
        val attachedCred = new JSONObject(Base64Util.decodeToStr(issuedCred.`credentials~attach`.head.data.base64))
        runAsSync(
          Anoncreds.proverStoreCredential(
            holderWallet, credId, credReqCreated.credReqMetadataJson, attachedCred.toString(), credDefCreated.json, null)
          .map (c => CredStored(c))
        )
      }
    }

    "when tried to present proof" - {
      "should be successful" in {
        //verifier creates presentation request
        val schema = runAsSync(vdrAdapter.resolveSchema(schemaCreated.fqId))
        schema.fqId shouldBe s"$UNQUALIFIED_LEDGER_PREFIX:${issuerKey.did}/anoncreds/v0/SCHEMA/employment/1.0"

        val req = Request("employment",
          Option(List(
            ProofAttribute(
              None,
              Option(List("name", "company")),
              None,
              None,
              self_attest_allowed = false)
          )),
          None,
          None
        )
        val requestPresentation = buildPresentationReq(req)

        //holder get creds for proof req
        val proofRequestJson = extractAttachment(AttIds.request0, requestPresentation.`request_presentations~attach`).get
        val creds = runAsSync(Anoncreds.proverGetCredentialsForProofReq(holderWallet, proofRequestJson)
          .map(c => Try(CredForProofReqCreated(c))))
        val availableCreds = creds.map(_.cred).map(DefaultMsgCodec.fromJson[AvailableCredentials](_))
        val (credsToUseJsonStr, ids) = credentialsToUse(availableCreds, Map.empty)
        val credsToUseJSONObject = new JSONObject(credsToUseJsonStr.get)
        val reqAttributesJSONObject = credsToUseJSONObject.getJSONObject("requested_attributes")
        val attrsJSONObject = reqAttributesJSONObject.getJSONObject("name:company")
        attrsJSONObject.getBoolean("revealed") shouldBe true
      }
    }
  }

  private def bootstrapIssuerDIDViaVDRTools(did: DidStr, verKey: VerKeyStr, role: String): Unit = {
    val preparedTxn = runAsSync {
      vdrAdapter.prepareDidTxn(s"""{"dest":"$did", "verkey": "$verKey", "role": "$role"}""", trusteeFqDid, None)
    }
    val submittedTxn = signAndSubmitTxn(trusteeWallet, trusteeKey.verKey, preparedTxn)

    val response = new JSONObject(submittedTxn.response)
    response.getString("op") shouldBe "REPLY"
    val result = response.getJSONObject("result")
    result.getInt("ver") shouldBe 1
    val reqSignature = result.getJSONObject("reqSignature")
    reqSignature.getString("type") shouldBe "ED25519"   //TODO: is this ok?
    val txnMetadata = result.getJSONObject("txnMetadata")
    txnMetadata.getInt("seqNo") > 0 shouldBe true

    val txn = result.getJSONObject("txn")
    txn.getInt("type") shouldBe 1
    txn.getInt("protocolVersion") shouldBe 2
    val txnInnerMetadata = txn.getJSONObject("metadata")
  }

  private def bootstrapIssuerDIDLegacy(did: DidStr, verKey: VerKeyStr, role: String): Unit = {
    legacyLedgerUtil.bootstrapNewDID(did, verKey, role)
  }

  private def createOrOpenWallet(walletId: String)
                        (implicit ec: ExecutionContext): Wallet = {
    val testAppConfig = new TestAppConfig
    val walletParam = generateWalletParamSync(walletId, testAppConfig, LibIndyWalletProvider)
    val wallet: Wallet = try {
      LibIndyWalletProvider.openSync(walletParam.walletName, walletParam.encryptionKey, walletParam.walletConfig).wallet
    } catch {
      case _: WalletDoesNotExist =>
        LibIndyWalletProvider.createSync(walletParam.walletName, walletParam.encryptionKey, walletParam.walletConfig)
        LibIndyWalletProvider.openSync(walletParam.walletName, walletParam.encryptionKey, walletParam.walletConfig).wallet
    }
    wallet
  }

  private def createNewKey(wallet: Wallet, cnk: CreateNewKey): NewKeyCreated = {
    val DIDJson = new DidJSONParameters.CreateAndStoreMyDidJSONParameter(
      cnk.DID.orNull, cnk.seed.orNull, null, null)
    runAsSync(
      Did
        .createAndStoreMyDid(wallet, DIDJson.toJson)
        .map( r => NewKeyCreated(r.getDid, r.getVerkey))
    )
  }

  private def buildPresentationReq(req: Request): RequestPresentation = {
    val proofRequest = ProofRequestUtil.requestToProofRequest(req)
    val proofRequestStr = proofRequest.map(DefaultMsgCodec.toJson)
    proofRequestStr match {
      case Success(str) => Msg.RequestPresentation("", Vector(buildAttachment(Some(AttIds.request0), str)))
      case Failure(e)   => throw e
    }
  }

  private def buildCredIssued(createdCred: CredCreated): IssueCred = {
    val attachment = buildAttachment(Some("libindy-cred-0"), payload=createdCred.cred)
    val attachmentEventObject = toAttachmentObject(attachment)
    val credIssued = CredIssued(Seq(attachmentEventObject), "")
    IssueCred(Vector(attachment), Option(credIssued.comment), `~please_ack` = None)
  }

  private def buildOfferCred(credOffer:  CredOfferCreated): OfferCred = {
    val credPreview = buildCredPreview(Map("name" -> "user-name", "company" -> "company-name"))
    val credPreviewEventObject = credPreview.toOption.map(_.toCredPreviewObject)
    val attachment = buildAttachment(Some("libindy-cred-offer-0"), payload = credOffer.offer)
    val attachmentEventObject = toAttachmentObject(attachment)
    val credOffered = CredOffered(
      credPreviewEventObject,
      Seq(attachmentEventObject),
      "",
      None
    )
    OfferCred(
      credPreview,
      Vector(attachment),
      Option(credOffered.comment),
      None
    )
  }

  private def buildRequestCred(credReqCreated: CredReqCreated): RequestCred = {
    val attachment = buildAttachment(Some("libindy-cred-req-0"), payload = credReqCreated.credReqJson)
    val attachmentEventObject = IssueCredential.toAttachmentObject(attachment)
    val credRequested = CredRequested(Seq(attachmentEventObject))
    RequestCred(Vector(attachment), Option(credRequested.comment))
  }

  private def signAndSubmitTxn(wallet: Wallet,
                               signerVerKey: VerKeyStr,
                               preparedTxn: PreparedTxn): SubmittedTxn = {
    val signedMsg = runAsSync(
      Crypto.cryptoSign(wallet, signerVerKey, preparedTxn.bytesToSign)
        .map(SignedMsg(_, signerVerKey))
    )

    runAsSync {
      vdrAdapter.submitTxn(
        preparedTxn,
        signedMsg.signatureResult.signature,
        Array.empty
      )
    }
  }

  var schemaCreated: Schema = null
  var credDefCreated: CredDef = null
  val credId: String = UUID.randomUUID().toString

  private def runAsSync[T](f: Future[T]): T = {
      Await.result(f, 25.seconds)
  }

  private def createVDRAdapter(vdrToolsFactory: VDRToolsFactory, appConfig: AppConfig)
                              (implicit ec: ExecutionContext, as: ActorSystem): VDRActorAdapter = {
    new VDRActorAdapter(
      vdrToolsFactory,
      VDRToolsConfig.load(appConfig.config),
      None
    )(ec, as.toTyped)
  }

  lazy val INDY_NAMESPACE = "indy:sovrin"
  lazy val UNQUALIFIED_LEDGER_PREFIX = s"did:$INDY_NAMESPACE"
  lazy val ledgerPrefixMapping = Map("did:sov" -> UNQUALIFIED_LEDGER_PREFIX)

  lazy val vdrConfig: Config = ConfigFactory.parseString(
    s"""
      |verity {
      |  lib-vdrtools {
      |
      |    library-dir-location = "/usr/lib"
      |
      |    flavor = "async"
      |
      |    ledger {
      |      indy {
      |        transaction_author_agreement = {
      |          enabled = true
      |
      |          # auto-accept is strictly used for testing and should not be documented as a production feature
      |          auto-accept = true
      |
      |          agreements {
      |            "1.0.0" {
      |               "digest" = "a0ab0aada7582d4d211bf9355f36482e5cb33eeb46502a71e6cc7fea57bb8305"
      |               "mechanism" = "on_file"
      |               "time-of-acceptance" = ${LocalDate.now().toString}
      |             }
      |          }
      |        }
      |
      |        # ledger pool transaction file location
      |        genesis-txn-file-location = "target/genesis.txt"  //environment variable if set, override above value
      |      }
      |    }
      |
      |    wallet {
      |      # this value is provided to libindy's create wallet api by which it knows which type of wallet we want to use
      |      # for now, it only supports "default" and "mysql"
      |      type = "mysql"
      |    }
      |
      |  }
      |  vdr {
      |    unqualified-ledger-prefix = "$UNQUALIFIED_LEDGER_PREFIX"
      |    ledgers: [
      |      {
      |        type = "indy"
      |        namespaces = ["$INDY_NAMESPACE"]
      |        genesis-txn-file-location = "target/genesis.txt"
      |
      |        transaction-author-agreement: {
      |          text: "TAA for sandbox ledger"
      |          version: "1.0.0"
      |          digest: "a0ab0aada7582d4d211bf9355f36482e5cb33eeb46502a71e6cc7fea57bb8305"
      |          time-of-acceptance: ${Instant.now.getEpochSecond}
      |          mechanism: "on_file"
      |        }
      |      }
      |    ]
      |  }
      |}
      |""".stripMargin
  )

  lazy val vdrBuilderFactory: VDRToolsFactory = () => new VdrToolsBuilderImpl(appConfig)
  lazy val vdrAdapter: VDRAdapter = createVDRAdapter(vdrBuilderFactory, appConfig)
  lazy val appConfig: AppConfig = new AppConfig {
    var config: Config = vdrConfig
  }
}
