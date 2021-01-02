package com.evernym.verity.actor.wallet

import java.util.UUID

import akka.actor.PoisonPill
import akka.testkit.ImplicitSender
import com.evernym.verity.actor.agentRegion
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.protocol.engine.external_api_access.WalletAccess.KEY_ED25519
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.RevocationDetails
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.util.JsonUtil.seqToJson
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyParam, WalletAPIParam}
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.ledger.Ledger.buildGetNymRequest
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration.DurationInt


class WalletActorSpec
  extends ActorSpec
    with BasicSpec
    with ImplicitSender
    with Eventually {

  lazy val walletActorEntityId1: String = UUID.randomUUID().toString
  lazy val walletActorEntityId2: String = UUID.randomUUID().toString
  lazy val walletActor1: agentRegion = agentRegion(walletActorEntityId1, walletRegionActor)
  lazy val walletActor2: agentRegion = agentRegion(walletActorEntityId2, walletRegionActor)

  val testDID = "did:sov:NcysrVCeLU1WNdJdLYxU6g"
  val keyParam: KeyParam = KeyParam(Right(GetVerKeyByDIDParam(testDID, getKeyFromPool = false)))
  val testByteMsg: Array[Byte] = "test message".getBytes()

  def createKeyParam(): KeyParam = {
    walletActor1 ! CreateNewKey()
    val keys = expectMsgType[NewKeyCreated]
    KeyParam(Right(GetVerKeyByDIDParam(keys.did, getKeyFromPool = false)))
  }

  "WalletActor" - {

    "when sent CreateWallet command" - {
      "should respond with WalletCreated" in {
        walletActor1 ! CreateWallet
        expectMsgType[WalletCreated.type]
        walletActor2 ! CreateWallet
        expectMsgType[WalletCreated.type]
      }
    }

    "when sent CreateWallet command again" - {
      "should respond with WalletAlreadyCreated" in {
        walletActor1 ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
        walletActor2 ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
      }
    }

    "when sent CreateNewKey command" - {
      "should respond with NewKeyCreated" in {
        walletActor1 ! CreateNewKey()
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent PoisonPill command" - {
      "should stop the actor" in {
        walletActor1 ! PoisonPill
        expectNoMessage()
      }
    }

    "when sent CreateWallet command" - {
      "should respond with WalletAlreadyCreated" in {
        walletActor1 ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
      }
    }

    "when sent CreateNewKey command again" - {
      "should respond with NewKeyCreated" in {
        walletActor1 ! CreateNewKey()
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent SignLedgerRequest command" - {
      "should respond with LedgerRequest" in {
        walletActor1 ! CreateNewKey()
        val keys = expectMsgType[NewKeyCreated]
        val req = buildGetNymRequest("did:sov:NcysrVCeLU1WNdJdLYxU6g", testDID).get
        walletActor1 ! SignLedgerRequest(LedgerRequest(req), Submitter(keys.did, Some(WalletAPIParam(walletActorEntityId1))))
        expectMsgType[LedgerRequest]
      }
    }

    "when sent CreateDID command" - {
      "should respond with NewKeyCreated" in {
        walletActor1 ! CreateDID(KEY_ED25519)
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent StoreTheirKey command" - {
      "should respond with TheirKeyStored" in {
        walletActor1 ! StoreTheirKey(testDID, "CnToPx3rPHNaXkMMtdPTnsK45pSHvP1e4BzNrk3oSVgr")
        expectMsgType[TheirKeyStored]
      }
    }

    "when sent GetVerKey command" - {
      "should respond with VerKey" in {
        walletActor1 ! GetVerKey(keyParam)
        val vk = expectMsgType[VerKey]
        vk shouldBe "CnToPx3rPHNaXkMMtdPTnsK45pSHvP1e4BzNrk3oSVgr"
      }
    }

    "when sent GetVerKeyOpt command" - {
      "should respond with optional VerKey" in {
        walletActor1 ! GetVerKeyOpt(keyParam)
        expectMsgType[Option[VerKey]]
      }
    }

    "when sent SignMsg command and check result with VerifySigByKeyParam command" - {
      "should respond with success VerifySigResult" in {
        val newKeyParam = createKeyParam()
        walletActor1 ! SignMsg(newKeyParam, testByteMsg)
        val signature = expectMsgType[Array[Byte]]
        walletActor1 ! VerifySigByKeyParam(newKeyParam, challenge = testByteMsg, signature = signature)
        val verifyResult = expectMsgType[VerifySigResult]
        assert(verifyResult.verified)
      }
    }

    "when sent PackMsg command" - {
      "should respond with PackedMsg be successfully unpacked via UnpackMsg" in {
        walletActor1 ! PackMsg(testByteMsg, recipVerKeyParams = Set(createKeyParam()), senderVerKeyParam = Some(createKeyParam()))
        val packedMsg = expectMsgType[PackedMsg]
        walletActor2 ! UnpackMsg(packedMsg.msg)
        expectMsgType[WalletCmdErrorResponse]
        walletActor1 ! UnpackMsg(packedMsg.msg)
        val binaryUnpackedMsg = expectMsgType[UnpackedMsg]
        val jsonStringMsg = new String(binaryUnpackedMsg.msg)
        val unpackedMsg = DefaultMsgCodec.fromJson[Map[String, String]](jsonStringMsg)
        assert(testByteMsg.sameElements(unpackedMsg("message")))
      }
    }

    "when sent LegacyPackMsg command" - {
      "should respond with PackedMsg be successfully unpacked via LegacyUnpackMsg" in {
        val senderVerKey = Some(createKeyParam())
        val recipVerKeys = createKeyParam()
        walletActor1 ! LegacyPackMsg(testByteMsg, Set(recipVerKeys), senderVerKey)
        val packedMsg = expectMsgType[PackedMsg]
        walletActor1 ! LegacyUnpackMsg(packedMsg.msg, fromVerKeyParam = Some(recipVerKeys), isAnonCryptedMsg = false)
        val unpackedMsg = expectMsgType[UnpackedMsg]
        assert(testByteMsg.sameElements(unpackedMsg.msg))
      }
    }

    "when sent CreateCredReq command" - {
      "should respond with CredReq in a json string" in {
        walletActor1 ! CreateNewKey()
        val keys = expectMsgType[NewKeyCreated]

        walletActor1 ! CreateMasterSecret("masterSecretId")
        val masterSecretId = expectMsgType[String]

        val schema = Anoncreds.issuerCreateSchema(keys.did, "name", "version",
          seqToJson(Array("attr1", "attr2"))).get()
        walletActor1 ! CreateCredDef(issuerDID = keys.did,
          schemaJson = schema.getSchemaJson,
          tag = "tag",
          sigType = Some("CL"),
          revocationDetails = Some(RevocationDetails(support_revocation = false, "tails_file", 5).toString))
        val createdCredDef = expectMsgType[CreatedCredDef](60.second)

        walletActor1 ! CreateCredOffer(createdCredDef.credDefId)
        val createCredOffer = expectMsgType[String]

        walletActor1 ! CreateCredReq(createdCredDef.credDefId, proverDID = keys.did,
          createdCredDef.credDefJson, createCredOffer, masterSecretId)
        val credReqJson = expectMsgType[String]

      }
    }


    "when sent CreateCred command with invalid value" - {
      "should respond with error message" in {
        walletActor1 ! CreateCred("credOfferJson", "credReqJson", "credValuesJson",
          "revRegistryId", -1)
        expectMsgType[WalletCmdErrorResponse]
      }
    }

    "when sent CreateCred command" - {
      "should respond with a created credential" ignore {
        walletActor1 ! CreateCred("credOfferJson", "credReqJson", "credValuesJson",
          "revRegistryId", -1)
        expectMsgType[String]
      }
    }

    "when sent CredForProofReq command with invalid value" - {
      "should respond with error message" in {
        walletActor1 ! CredForProofReq("proofRequest")
        expectMsgType[WalletCmdErrorResponse]
      }
    }

    "when sent CredForProofReq command" - {
      "should respond with a credential for the proof req" ignore {
        walletActor1 ! CredForProofReq("proofRequest")
        expectMsgType[String]
      }
    }

    "when sent CreateProof command with invalid value" - {
      "should respond with error message" in {
        walletActor1 ! CreateProof("proofRequest", "usedCredentials", "schemas",
          "credentialDefs", "revStates", "masterSecret")
        expectMsgType[WalletCmdErrorResponse]
      }
    }

    "when sent CreateProof command" - {
      "should respond with a proof" ignore {
        walletActor1 ! CreateProof("proofRequest", "usedCredentials", "schemas",
          "credentialDefs", "revStates", "masterSecret")
        expectMsgType[String]
      }
    }
  }
}