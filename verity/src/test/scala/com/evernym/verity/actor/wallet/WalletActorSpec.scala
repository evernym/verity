package com.evernym.verity.actor.wallet

import akka.actor.PoisonPill
import akka.testkit.ImplicitSender
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.agentRegion
import com.evernym.verity.actor.testkit.{ActorSpec, CommonSpecUtil}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.didcomm.decorators.AttachmentDescriptor.buildAttachment
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess.KEY_ED25519
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredential
import com.evernym.verity.protocol.protocols.presentproof.v_1_0.Ctl.Request
import com.evernym.verity.protocol.protocols.presentproof.v_1_0._
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.RevocationDetails
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.JsonUtil.seqToJson
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyParam, WalletAPIParam}
import com.typesafe.scalalogging.Logger
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.ledger.Ledger.buildGetNymRequest
import org.scalatest.concurrent.Eventually

import java.util.UUID
import scala.concurrent.duration.DurationInt


class WalletActorSpec
  extends ActorSpec
    with BasicSpec
    with ImplicitSender
    with Eventually {

  val logger: Logger = getLoggerByClass(classOf[WalletActorSpec])

  lazy val issuerKeySeed: String = UUID.randomUUID().toString.replace("-", "")
  lazy val holderKeySeed: String = UUID.randomUUID().toString.replace("-", "")
  lazy val verifierKeySeed: String = UUID.randomUUID().toString.replace("-", "")

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
        issuerWalletActor ! CreateWallet
        expectMsgType[WalletCreated.type]
        holderWalletActor ! CreateWallet
        expectMsgType[WalletCreated.type]
        verifierWalletActor ! CreateWallet
        expectMsgType[WalletCreated.type]
      }
    }

    "when sent CreateWallet command again" - {
      "should respond with WalletAlreadyCreated" in {
        issuerWalletActor ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
        holderWalletActor ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
        verifierWalletActor ! CreateWallet
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
        issuerWalletActor ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
        holderWalletActor ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
        verifierWalletActor ! CreateWallet
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
        val req = buildGetNymRequest(issuerDidPair.DID, issuerDidPair.DID).get
        issuerWalletActor ! SignLedgerRequest(LedgerRequest(req),
          Submitter(issuerDidPair.DID, Some(WalletAPIParam(issuerWalletActor.id))))
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
        issuerWalletActor ! StoreTheirKey(holderDidPair.DID, holderDidPair.verKey)
        expectMsgType[TheirKeyStored]
        holderWalletActor ! StoreTheirKey(issuerDidPair.DID, issuerDidPair.verKey)
        expectMsgType[TheirKeyStored]
        verifierWalletActor ! StoreTheirKey(holderDidPair.DID, holderDidPair.verKey)
        expectMsgType[TheirKeyStored]
      }
    }

    "when sent GetVerKey command" - {
      "should respond with VerKey" in {
        issuerWalletActor ! GetVerKey(KeyParam(Right(GetVerKeyByDIDParam(holderDidPair.DID, getKeyFromPool = false))))
        val vk1 = expectMsgType[VerKey]
        vk1 shouldBe holderDidPair.verKey

        holderWalletActor ! GetVerKey(KeyParam(Right(GetVerKeyByDIDParam(issuerDidPair.DID, getKeyFromPool = false))))
        val vk2 = expectMsgType[VerKey]
        vk2 shouldBe issuerDidPair.verKey

        verifierWalletActor ! GetVerKey(KeyParam(Right(GetVerKeyByDIDParam(holderDidPair.DID, getKeyFromPool = false))))
        val vk3 = expectMsgType[VerKey]
        vk3 shouldBe holderDidPair.verKey
      }
    }

    "when sent GetVerKeyOpt command" - {
      "should respond with optional VerKey" in {
        issuerWalletActor ! GetVerKeyOpt(KeyParam(Right(GetVerKeyByDIDParam(holderDidPair.DID, getKeyFromPool = false))))
        expectMsgType[Option[VerKey]]
        holderWalletActor ! GetVerKeyOpt(KeyParam(Right(GetVerKeyByDIDParam(issuerDidPair.DID, getKeyFromPool = false))))
        expectMsgType[Option[VerKey]]
        verifierWalletActor ! GetVerKeyOpt(KeyParam(Right(GetVerKeyByDIDParam(holderDidPair.DID, getKeyFromPool = false))))
        expectMsgType[Option[VerKey]]
      }
    }

    "when sent SignMsg command and check result with VerifySigByKeyParam command" - {
      "should respond with success VerifySigResult" in {
        val newIssuerKey = createKeyInWallet(issuerWalletActor)
        storeTheirKeyInWallet(newIssuerKey, holderWalletActor)
        val keyParam = KeyParam(Right(GetVerKeyByDIDParam(newIssuerKey.did, getKeyFromPool = false)))

        issuerWalletActor ! SignMsg(keyParam, testByteMsg)
        val signature = expectMsgType[Array[Byte]]

        holderWalletActor ! VerifySigByKeyParam(keyParam, challenge = testByteMsg, signature = signature)
        val verifyResult = expectMsgType[VerifySigResult]
        assert(verifyResult.verified)
      }
    }

    "when sent PackMsg command" - {
      "should respond with PackedMsg be successfully unpacked via UnpackMsg" in {
        val issuerKey = createKeyInWallet(issuerWalletActor)
        val issuerKeyParam = KeyParam(Right(GetVerKeyByDIDParam(issuerKey.did, getKeyFromPool = false)))
        storeTheirKeyInWallet(issuerKey, holderWalletActor)

        val proverKey = createKeyInWallet(holderWalletActor)
        val proverKeyParam = KeyParam(Right(GetVerKeyByDIDParam(proverKey.did, getKeyFromPool = false)))
        storeTheirKeyInWallet(proverKey, issuerWalletActor)

        issuerWalletActor ! PackMsg(testByteMsg, recipVerKeyParams = Set(proverKeyParam), senderVerKeyParam = Some(issuerKeyParam))
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
        val issuerKeyParam = KeyParam(Right(GetVerKeyByDIDParam(issuerKey.did, getKeyFromPool = false)))
        storeTheirKeyInWallet(issuerKey, holderWalletActor)

        val proverKey = createKeyInWallet(holderWalletActor)
        val proverKeyParam = KeyParam(Right(GetVerKeyByDIDParam(proverKey.did, getKeyFromPool = false)))
        storeTheirKeyInWallet(proverKey, issuerWalletActor)

        issuerWalletActor ! LegacyPackMsg(testByteMsg, Set(proverKeyParam), Some(issuerKeyParam))
        val packedMsg = expectMsgType[PackedMsg]

        holderWalletActor ! LegacyUnpackMsg(packedMsg.msg, fromVerKeyParam = Some(proverKeyParam), isAnonCryptedMsg = false)
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
        withCredReceived({ cd: CredData =>
          val verifierProofRequest = {
            val proofAttributes = List(ProofAttribute(Option("age"), None, None, None, self_attest_allowed=true))
            val req = Request("", Option(proofAttributes), None, None, None)
            val proofRequest = ProofRequestUtil.requestToProofRequest(req)
            val proofReqStr = proofRequest.map(DefaultMsgCodec.toJson).get
            Msg.RequestPresentation("", Vector(buildAttachment(Some(AttIds.request0), proofReqStr)))
          }
          val proofReqStr = PresentProof.extractRequest(verifierProofRequest).get
          holderWalletActor ! CredForProofReq(proofReqStr)
          val credForProofReq = expectMsgType[String]
          logger.info("credForProofReq: " + credForProofReq)
        })
      }
    }

    "when sent CreateProof command with invalid value" - {
      "should respond with error message" in {
        holderWalletActor ! CreateProof("proofRequest", "usedCredentials", "schemas",
          "credentialDefs", "revStates", "masterSecret")
        expectMsgType[WalletCmdErrorResponse]
      }
    }

    "when sent CreateProof command" - {
      "should respond with a proof" ignore {
        holderWalletActor ! CreateProof("proofRequest", "usedCredentials", "schemas",
          "credentialDefs", "revStates", "masterSecret")
        expectMsgType[String]
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

  def withCredReceived[T](f: CredData => T): T = {
    val crd = prepareBasicCredReqSetup()
    val credValues: Map[String, String] = Map(
      "name" -> "Joe",
      "age"  -> "41"
    )
    val credPreview = IssueCredential.buildCredPreview(credValues)
    val credValuesJson = IssueCredential.buildCredValueJson(credPreview)
    issuerWalletActor ! CreateCred(crd.credOffer, crd.credReq, credValuesJson, null, -1)
    val cred = expectMsgType[String]
    val cd = CredData(cred, crd)
    holderWalletActor ! StoreCred(UUID.randomUUID().toString, crd.credDef.credDefJson, crd.credReqMetaData, cred, null)
    expectMsgType[String]

    f(cd)
  }

  def withCredReqCreated[T](f: CredReqData => T): T = {
    val crs = prepareBasicCredReqSetup()
    f(crs)
  }

  def prepareBasicCredReqSetup(): CredReqData = {
    logger.info("new key created")
    val schema = Anoncreds.issuerCreateSchema(issuerDidPair.DID, "test-schema", "1.0",
      seqToJson(Array("name", "age"))).get()
    logger.info("schema created")
    issuerWalletActor ! CreateCredDef(issuerDID = issuerDidPair.DID,
      schemaJson = schema.getSchemaJson,
      tag = "tag",
      sigType = Some("CL"),
      revocationDetails = Some(RevocationDetails(support_revocation = false, "tails_file", 5).toString))
    val createdCredDef = expectMsgType[CreatedCredDef](60.second)
    logger.info("cred def created")

    issuerWalletActor ! CreateCredOffer(createdCredDef.credDefId)
    val credOfferJson = expectMsgType[String]
    logger.info("cred offer created")

    holderWalletActor ! CreateMasterSecret(UUID.randomUUID().toString)
    val masterSecretId = expectMsgType[String]
    logger.info("master secret created")

    holderWalletActor ! CreateCredReq(createdCredDef.credDefId, proverDID = holderDidPair.DID,
      createdCredDef.credDefJson, credOfferJson, masterSecretId)
    val credReq = expectMsgType[CreatedCredReq]
    logger.info("cred req created")

    CredReqData(createdCredDef, credOfferJson, credReq.credReqJson, credReq.credReqMetadataJson)
  }

}

case class CredReqData(credDef: CreatedCredDef, credOffer: String, credReq: String, credReqMetaData: String)
case class CredData(cred: String, credReqData: CredReqData)
