package com.evernym.verity.actor.wallet

import java.util.UUID

import akka.actor.PoisonPill
import akka.testkit.ImplicitSender
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agentRegion
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.{ActorSpec, CommonSpecUtil}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.did.{DidPair, VerKeyStr}
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.did.didcomm.v1.decorators.AttachmentDescriptor.buildAttachment
import com.evernym.verity.protocol.engine.asyncapi.wallet.WalletAccess.KEY_ED25519
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredential
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.{AttIds, Msg, PresentProof, ProofAttribute, ProofRequestUtil}
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.RevocationDetails
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.JsonUtil.seqToJson
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.ledger.Ledger.buildGetNymRequest
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration.DurationInt


class WalletActorSpec
  extends ActorSpec
    with BasicSpec
    with ImplicitSender
    with Eventually {

  val logger: Logger = getLoggerByClass(classOf[WalletActorSpec])

  def newKeySeed: String = UUID.randomUUID().toString.replace("-", "")

  lazy val issuerKeySeed: String = newKeySeed
  lazy val holderKeySeed: String = newKeySeed
  lazy val verifierKeySeed: String = newKeySeed

  lazy val issuerDidPair: DidPair = CommonSpecUtil.generateNewDid(Option(issuerKeySeed))
  lazy val holderDidPair: DidPair = CommonSpecUtil.generateNewDid(Option(holderKeySeed))
  lazy val verifierDidPair: DidPair = CommonSpecUtil.generateNewDid(Option(verifierKeySeed))

  lazy val issuerWalletActor: agentRegion = agentRegion(UUID.randomUUID().toString, walletRegionActor)
  lazy val holderWalletActor: agentRegion = agentRegion(UUID.randomUUID().toString, walletRegionActor)
  lazy val verifierWalletActor: agentRegion = agentRegion(UUID.randomUUID().toString, walletRegionActor)

  val testByteMsg: Array[Byte] = "test message".getBytes()

  "WalletActor" - {

    "when sent CreateWallet command" - {
      "should respond with WalletCreated" in {
        issuerWalletActor ! CreateWallet()
        expectMsgType[WalletCreated.type]
        holderWalletActor ! CreateWallet()
        expectMsgType[WalletCreated.type]
        verifierWalletActor ! CreateWallet()
        expectMsgType[WalletCreated.type]
      }
    }

    "when sent CreateWallet command again" - {
      "should respond with WalletAlreadyCreated" in {
        issuerWalletActor ! CreateWallet()
        expectMsgType[WalletAlreadyCreated.type]
        holderWalletActor ! CreateWallet()
        expectMsgType[WalletAlreadyCreated.type]
        verifierWalletActor ! CreateWallet()
        expectMsgType[WalletAlreadyCreated.type]
      }
    }

    "when sent CreateNewKey command" - {
      "should respond with NewKeyCreated" in {
        issuerWalletActor ! CreateNewKey(seed = Option(issuerKeySeed))
        expectMsgType[NewKeyCreated]
        holderWalletActor ! CreateNewKey(seed = Option(holderKeySeed))
        expectMsgType[NewKeyCreated]
        verifierWalletActor ! CreateNewKey(seed = Option(verifierKeySeed))
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent PoisonPill command" - {
      "should stop the actor" in {
        issuerWalletActor ! PoisonPill
        expectNoMessage()
        holderWalletActor ! PoisonPill
        expectNoMessage()
        verifierWalletActor ! PoisonPill
        expectNoMessage()
      }
    }

    "when sent CreateWallet command after poison pill" - {
      "should respond with WalletAlreadyCreated" in {
        issuerWalletActor ! CreateWallet()
        expectMsgType[WalletAlreadyCreated.type]
        holderWalletActor ! CreateWallet()
        expectMsgType[WalletAlreadyCreated.type]
        verifierWalletActor ! CreateWallet()
        expectMsgType[WalletAlreadyCreated.type]
      }
    }

    "when sent CreateNewKey command again" - {
      "should respond with NewKeyCreated" in {
        issuerWalletActor ! CreateNewKey()
        expectMsgType[NewKeyCreated]
        holderWalletActor ! CreateNewKey()
        expectMsgType[NewKeyCreated]
        verifierWalletActor ! CreateNewKey()
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent SignLedgerRequest command" - {
      "should respond with LedgerRequest" in {
        val req = buildGetNymRequest(issuerDidPair.did, issuerDidPair.did).get
        issuerWalletActor ! SignLedgerRequest(LedgerRequest(req),
          Submitter(issuerDidPair.did, Some(WalletAPIParam(issuerWalletActor.id))))
        expectMsgType[LedgerRequest]
      }
    }

    "when sent CreateDID command" - {
      "should respond with NewKeyCreated" in {
        issuerWalletActor ! CreateDID(KEY_ED25519)
        expectMsgType[NewKeyCreated]
        holderWalletActor ! CreateDID(KEY_ED25519)
        expectMsgType[NewKeyCreated]
        verifierWalletActor ! CreateDID(KEY_ED25519)
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent StoreTheirKey command" - {
      "should respond with TheirKeyStored" in {
        issuerWalletActor ! StoreTheirKey(holderDidPair.did, holderDidPair.verKey)
        expectMsgType[TheirKeyStored]
        holderWalletActor ! StoreTheirKey(issuerDidPair.did, issuerDidPair.verKey)
        expectMsgType[TheirKeyStored]
        verifierWalletActor ! StoreTheirKey(holderDidPair.did, holderDidPair.verKey)
        expectMsgType[TheirKeyStored]
      }
    }

    "when sent GetVerKey command" - {
      "should respond with VerKey" in {
        issuerWalletActor ! GetVerKey(holderDidPair.did)
        val vk1 = expectMsgType[GetVerKeyResp]
        vk1 shouldBe GetVerKeyResp(holderDidPair.verKey)

        holderWalletActor ! GetVerKey(issuerDidPair.did)
        val vk2 = expectMsgType[GetVerKeyResp]
        vk2 shouldBe GetVerKeyResp(issuerDidPair.verKey)

        verifierWalletActor ! GetVerKey(holderDidPair.did)
        val vk3 = expectMsgType[GetVerKeyResp]
        vk3 shouldBe GetVerKeyResp(holderDidPair.verKey)
      }
    }

    "when sent GetVerKeyOpt command" - {
      "should respond with optional VerKey" in {
        issuerWalletActor ! GetVerKeyOpt(holderDidPair.did)
        expectMsgType[GetVerKeyOptResp]
        holderWalletActor ! GetVerKeyOpt(issuerDidPair.did)
        expectMsgType[GetVerKeyOptResp]
        verifierWalletActor ! GetVerKeyOpt(holderDidPair.did)
        expectMsgType[GetVerKeyOptResp]
      }
    }

    "when sent SignMsg command and check result with VerifySignature command" - {
      "should respond with success VerifySigResult" in {
        val newIssuerKey = createKeyInWallet(issuerWalletActor)
        storeTheirKeyInWallet(newIssuerKey, holderWalletActor)
        val keyParam = KeyParam.fromDID(newIssuerKey.did)

        issuerWalletActor ! SignMsg(keyParam, testByteMsg)
        val sm = expectMsgType[SignedMsg]

        //if try to validate signature against wrong ver key, it should respond with failed verification
        holderWalletActor ! VerifySignature(keyParam, challenge = testByteMsg, signature = sm.msg, Option(holderDidPair.verKey))
        val verifyResult1 = expectMsgType[VerifySigResult]
        verifyResult1.verified shouldBe false

        //if try to validate signature against correct ver key, it should respond with successful verification
        holderWalletActor ! VerifySignature(keyParam, challenge = testByteMsg, signature = sm.msg, Option(newIssuerKey.verKey))
        val verifyResult2 = expectMsgType[VerifySigResult]
        verifyResult2.verified shouldBe true

        //if try to validate signature, it should respond with successful verification
        holderWalletActor ! VerifySignature(keyParam, challenge = testByteMsg, signature = sm.msg)
        val verifyResult3 = expectMsgType[VerifySigResult]
        verifyResult3.verified shouldBe true
      }
    }

    "when sent PackMsg command" - {
      "should respond with PackedMsg be successfully unpacked via UnpackMsg" in {
        val issuerKey = createKeyInWallet(issuerWalletActor)
        val issuerKeyParam = KeyParam.fromDID(issuerKey.did)
        storeTheirKeyInWallet(issuerKey, holderWalletActor)

        val holderKey = createKeyInWallet(holderWalletActor)
        val holderKeyParam = KeyParam.fromDID(holderKey.did)
        storeTheirKeyInWallet(holderKey, issuerWalletActor)

        issuerWalletActor ! PackMsg(testByteMsg, recipVerKeyParams = Set(holderKeyParam), senderVerKeyParam = Some(issuerKeyParam))
        val packedMsg = expectMsgType[PackedMsg]
        issuerWalletActor ! UnpackMsg(packedMsg.msg)
        expectMsgType[WalletCmdErrorResponse]

        holderWalletActor ! UnpackMsg(packedMsg.msg)
        val binaryUnpackedMsg = expectMsgType[UnpackedMsg]
        val jsonStringMsg = new String(binaryUnpackedMsg.msg)
        val unpackedMsg = DefaultMsgCodec.fromJson[Map[String, String]](jsonStringMsg)
        assert(testByteMsg.sameElements(unpackedMsg("message")))
      }
    }

    "when sent LegacyPackMsg command" - {
      "should respond with PackedMsg be successfully unpacked via LegacyUnpackMsg" in {
        val issuerKey = createKeyInWallet(issuerWalletActor)
        val issuerKeyParam = KeyParam.fromDID(issuerKey.did)
        storeTheirKeyInWallet(issuerKey, holderWalletActor)

        val holderKey = createKeyInWallet(holderWalletActor)
        val holderKeyParam = KeyParam.fromDID(holderKey.did)
        storeTheirKeyInWallet(holderKey, issuerWalletActor)

        issuerWalletActor ! LegacyPackMsg(testByteMsg, Set(holderKeyParam), Some(issuerKeyParam))
        val packedMsg = expectMsgType[PackedMsg]

        holderWalletActor ! LegacyUnpackMsg(packedMsg.msg, fromVerKeyParam = Some(holderKeyParam), isAnonCryptedMsg = false)
        val unpackedMsg = expectMsgType[UnpackedMsg]
        assert(testByteMsg.sameElements(unpackedMsg.msg))
      }
    }

    "when sent CreateCredReq command" - {
      "should respond with CredReq in a json string" in {
        withCredReqCreated { crd: CredReqData =>
          //add assertions
          logger.info("cred req: " + crd.credReq)
        }
      }
    }

    "when sent CreateCred command with invalid value" - {
      "should respond with error message" in {
        issuerWalletActor ! CreateCred("credOfferJson", "credReqJson", "credValuesJson",
          "revRegistryId", -1)
        expectMsgType[WalletCmdErrorResponse]
      }
    }

    "when sent CreateCred command" - {
      "should respond with a created credential" in {
        withCredReceived({ crd: CredData =>
          logger.info("cred: " + crd.cred)
        })
      }
    }

    "when sent CredForProofReq command with invalid value" - {
      "should respond with error message" in {
        holderWalletActor ! CredForProofReq("proofRequest")
        expectMsgType[WalletCmdErrorResponse]
      }
    }

    "when sent CredForProofReq command" - {
      "should respond with a credential for the proof req" in {
        withCredForProofReqCreated({ cfpr: CredForProofReqData =>
          logger.info("credForProofReq: " + cfpr.credForProofReq)
        })
      }
    }

    "when sent CreateProof command with invalid value" - {
      "should respond with error message" in {
        holderWalletActor ! CreateProof("proofRequest", "usedCredentials", "schemas",
          "credentialDefs", "masterSecret", "revStates")
        expectMsgType[WalletCmdErrorResponse]
      }
    }

    "when sent CreateProof command" - {
      "should respond with a proof" in {
        withCredForProofReqCreated({ cfpr: CredForProofReqData =>
          val proofReq =
            s"""
               {
                  "nonce":"123432421212",
                  "name":"age-proof-req",
                  "version":"1.0",
                  "requested_attributes":{
                    "attest1":{"name":"name","self_attest_allowed":false},
                    "attest2":{"name":"age","self_attest_allowed":false}
                  },
                  "requested_predicates":{},
                  "ver":"1.0"
               }"""
          val requestedCredentials =
            s"""
              {
                "self_attested_attributes":{},
                "requested_attributes":{
                  "attest1":{"cred_id":"${cfpr.credData.credId}","revealed":true},
                  "attest2":{"cred_id":"${cfpr.credData.credId}","revealed":true}
                },
                "requested_predicates":{}
              }"""
          val schemasJson =
            s"""{
              "${cfpr.credData.credReqData.schemaId}": ${cfpr.credData.credReqData.schemaJson}
              }"""
          val credDefsJson =
            s"""{
              "${cfpr.credData.credReqData.credDef.credDefId}": ${cfpr.credData.credReqData.credDef.credDefJson}
              }"""
          val revStates = """{}"""
          holderWalletActor ! CreateProof(
            proofReq,
            requestedCredentials,
            schemasJson,
            credDefsJson,
            cfpr.credData.credReqData.masterSecretId,
            revStates
          )
          val proof = expectMsgType[ProofCreated].proof
          logger.info("proof: " + proof)
        })
      }
    }

    "when sent SetupNewWallet command for cloud agent setup" - {
      "should create wallet and keys" in {

        val domainDidPair: DidPair = CommonSpecUtil.generateNewDid(Option(newKeySeed))

        val newWalletActor = agentRegion(UUID.randomUUID().toString, walletRegionActor)
        newWalletActor ! SetupNewAgentWallet(Option(domainDidPair))
        val wsc = expectMsgType[AgentWalletSetupCompleted]
        wsc.ownerDidPair shouldBe domainDidPair

        newWalletActor ! GetVerKey(wsc.agentKey.did)
        val gvkr1 = expectMsgType[GetVerKeyResp]
        gvkr1.verKey shouldBe wsc.agentKey.verKey

        newWalletActor ! GetVerKey(wsc.ownerDidPair.did)
        val gvkr2 = expectMsgType[GetVerKeyResp]
        gvkr2.verKey shouldBe domainDidPair.verKey
      }
    }

    "when sent SetupNewWallet command for edge agent setup" - {
      "should create wallet and keys" in {

        val requesterVk: VerKeyStr = CommonSpecUtil.generateNewDid(Option(newKeySeed)).verKey

        val newWalletActor = agentRegion(UUID.randomUUID().toString, walletRegionActor)

        newWalletActor ! SetupNewAgentWallet(None)
        val wsc = expectMsgType[AgentWalletSetupCompleted]

        newWalletActor ! GetVerKey(wsc.agentKey.did)
        val gvkr1 = expectMsgType[GetVerKeyResp]
        gvkr1.verKey shouldBe wsc.agentKey.verKey

        newWalletActor ! GetVerKey(wsc.ownerDidPair.did)
        val gvkr2 = expectMsgType[GetVerKeyResp]
        gvkr2.verKey shouldBe wsc.ownerDidPair.verKey

        newWalletActor ! GetVerKeyOpt(requesterVk)
        val gvkr3 = expectMsgType[GetVerKeyOptResp]
        gvkr3.verKey shouldBe None
      }
    }

    "when sent Close command" - {
      "should close wallet" in {
        List(issuerWalletActor, holderWalletActor, verifierWalletActor).foreach { walletActor =>
          walletActor ! Close()
          expectMsgType[Done.type]
        }
      }
    }
  }

  def createKeyInWallet(walletActor: agentRegion): NewKeyCreated = {
    walletActor ! CreateNewKey()
    expectMsgType[NewKeyCreated]
  }

  def storeTheirKeyInWallet(nk: NewKeyCreated, walletActor: agentRegion): Unit = {
    walletActor ! StoreTheirKey(nk.did, nk.verKey)
    expectMsgType[TheirKeyStored]
  }

  def withCredForProofReqCreated[T](f: CredForProofReqData => T): T = {
    withCredReceived { cd: CredData =>
      val verifierProofRequest = {
        val proofAttributes = List(ProofAttribute(Option("age"), None, None, None, self_attest_allowed = true))
        val req = Request("", Option(proofAttributes), None, None, None)
        val proofRequest = ProofRequestUtil.requestToProofRequest(req)
        val proofReqStr = proofRequest.map(DefaultMsgCodec.toJson).get
        Msg.RequestPresentation("", Vector(buildAttachment(Some(AttIds.request0), proofReqStr)))
      }
      val proofReqStr = PresentProof.extractRequest(verifierProofRequest).get
      holderWalletActor ! CredForProofReq(proofReqStr)
      val credForProofReq = expectMsgType[CredForProofReqCreated].cred
      val data = CredForProofReqData(credForProofReq, cd)
      f(data)
    }
  }

  def withCredReceived[T](f: CredData => T): T = {
    val crd = prepareBasicCredReqSetup()
    val credValues: Map[String, String] = Map(
      "name" -> "Joe",
      "age"  -> "41"
    )
    val credPreview = IssueCredential.buildCredPreview(credValues)
    val credValuesJson = IssueCredential.buildCredValueJson(credPreview)
    issuerWalletActor ! CreateCred(crd.credOffer, crd.credReq, credValuesJson, null, -1)
    val cred = expectMsgType[CredCreated].cred
    holderWalletActor ! StoreCred(UUID.randomUUID().toString, crd.credDef.credDefJson, crd.credReqMetaData, cred, null)
    val credId = expectMsgType[CredStored].cred
    val cd = CredData(credId, cred, crd)
    f(cd)
  }

  def withCredReqCreated[T](f: CredReqData => T): T = {
    val crs = prepareBasicCredReqSetup()
    f(crs)
  }

  def prepareBasicCredReqSetup(): CredReqData = {
    logger.info("new key created")
    val schema = Anoncreds.issuerCreateSchema(issuerDidPair.did, "test-schema", "1.0",
      seqToJson(Array("name", "age"))).get()
    logger.info("schema created")

    issuerWalletActor ! CreateCredDef(issuerDID = issuerDidPair.did,
      schemaJson = schema.getSchemaJson,
      tag = "tag",
      sigType = Some("CL"),
      revocationDetails = Some(RevocationDetails(support_revocation = false, "tails_file", 5).toString))
    val createdCredDef = expectMsgType[CredDefCreated](60.second)
    logger.info("cred def created")

    issuerWalletActor ! CreateCredOffer(createdCredDef.credDefId)
    val credOfferJson = expectMsgType[CredOfferCreated].offer
    logger.info("cred offer created")

    holderWalletActor ! CreateMasterSecret(UUID.randomUUID().toString)
    val masterSecretId = expectMsgType[MasterSecretCreated].ms
    logger.info("master secret created")

    holderWalletActor ! CreateCredReq(createdCredDef.credDefId, proverDID = holderDidPair.did,
      createdCredDef.credDefJson, credOfferJson, masterSecretId)
    val credReq = expectMsgType[CredReqCreated]
    logger.info("cred req created: " + credReq)

    CredReqData(schema.getSchemaId, schema.getSchemaJson,
      createdCredDef, credOfferJson, masterSecretId,
      credReq.credReqJson, credReq.credReqMetadataJson)
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
}

case class CredReqData(schemaId: String,
                       schemaJson: String,
                       credDef: CredDefCreated,
                       credOffer: String,
                       masterSecretId: String,
                       credReq: String,
                       credReqMetaData: String)

case class CredData(credId: String, cred: String, credReqData: CredReqData)

case class CredForProofReqData(credForProofReq: String, credData: CredData)
