package com.evernym.verity.actor

import java.util.UUID

import akka.actor.PoisonPill
import akka.testkit.ImplicitSender
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import com.evernym.verity.actor.wallet._
import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet}
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.protocol.engine.VerKey
import com.evernym.verity.protocol.engine.WalletAccess.KEY_ED25519
import com.evernym.verity.protocol.protocols.writeCredentialDefinition.v_0_6.RevocationDetails
import com.evernym.verity.util.JsonUtil.seqToJson
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyInfo, WalletAPIParam}
import org.hyperledger.indy.sdk.anoncreds.Anoncreds
import org.hyperledger.indy.sdk.ledger.Ledger.buildGetNymRequest

import scala.concurrent.duration.DurationInt

class WalletActorSpec
  extends ActorSpec
    with BasicSpec
    with ImplicitSender
    with Eventually {

  lazy val walletActorEntityId: String = UUID.randomUUID().toString
  lazy val walletActor: agentRegion = agentRegion(walletActorEntityId, walletRegionActor)
  val testDID = "did:sov:NcysrVCeLU1WNdJdLYxU6g"
  val keyInfo: KeyInfo = KeyInfo(Right(GetVerKeyByDIDParam(testDID, getKeyFromPool = false)))
  val testByteMsg: Array[Byte] = "test message".getBytes()

  def createKeyInfo(): KeyInfo = {
    walletActor ! CreateNewKey()
    val keys = expectMsgType[NewKeyCreated]
    KeyInfo(Right(GetVerKeyByDIDParam(keys.did, getKeyFromPool = false)))
  }

  "WalletActor" - {

    "when sent CreateWallet command" - {
      "should respond with WalletCreated" in {
        walletActor ! CreateWallet
        expectMsgType[WalletCreated.type]
      }
    }

    "when sent CreateWallet command again" - {
      "should respond with WalletAlreadyCreated" in {
        walletActor ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
      }
    }

    "when sent CreateNewKey command" -{
      "should respond with NewKeyCreated" in {
        walletActor ! CreateNewKey()
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent PoisonPill command" -{
      "should stop the actor" in {
        walletActor ! PoisonPill
        expectNoMessage()
      }
    }

    "when sent CreateWallet command" - {
      "should respond with WalletAlreadyCreated" in {
        walletActor ! CreateWallet
        expectMsgType[WalletAlreadyCreated.type]
      }
    }

    "when sent CreateNewKey command again" -{
      "should respond with NewKeyCreated" in {
        walletActor ! CreateNewKey()
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent SignLedgerRequest command" - {
      "should respond with LedgerRequest" in {
        walletActor ! CreateNewKey()
        val keys = expectMsgType[NewKeyCreated]
        val req = buildGetNymRequest("did:sov:NcysrVCeLU1WNdJdLYxU6g", testDID).get
        walletActor ! SignLedgerRequest(LedgerRequest(req), Submitter(keys.did, Some(WalletAPIParam(walletActorEntityId))))
        expectMsgType[LedgerRequest]
      }
    }

    "when sent CreateDID command" - {
      "should respond with NewKeyCreated" in {
        walletActor ! CreateDID(KEY_ED25519)
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent StoreTheirKey command" - {
      "should respond with TheirKeyStored" in {
        walletActor ! StoreTheirKey(testDID, "CnToPx3rPHNaXkMMtdPTnsK45pSHvP1e4BzNrk3oSVgr")
        expectMsgType[TheirKeyStored]
      }
    }

    "when sent GetVerKey command" - {
      "should respond with VerKey" in {
        walletActor ! GetVerKey(keyInfo)
        expectMsgType[VerKey]
      }
    }

    "when sent GetVerKeyOpt command" - {
      "should respond with optional VerKey" in {
        walletActor ! GetVerKeyOpt(keyInfo)
        expectMsgType[Option[VerKey]]
      }
    }

    "when sent SignMsg command and check result with VerifySigByKeyInfo command" - {
      "should respond with success VerifySigResult" in {
        val newKeyInfo = createKeyInfo()
        walletActor ! SignMsg(newKeyInfo, testByteMsg)
        val signature = expectMsgType[Array[Byte]]
        walletActor ! VerifySigByKeyInfo(newKeyInfo, challenge = testByteMsg, signature = signature)
        val verifyResult = expectMsgType[VerifySigResult]
        assert(verifyResult.verified)
      }
    }

    "when sent PackMsg command" - {
      "should respond with PackedMsg be successfully unpacked via UnpackMsg" in {
        walletActor ! PackMsg(testByteMsg, recipVerKeys = Set(createKeyInfo()), senderVerKey = Some(createKeyInfo()))
        val packedMsg = expectMsgType[PackedMsg]
        walletActor ! UnpackMsg(packedMsg.msg)
        val binaryUnpackedMsg = expectMsgType[UnpackedMsg]
        val jsonStringMsg = new String(binaryUnpackedMsg.msg)
        val unpackedMsg = DefaultMsgCodec.fromJson[Map[String, String]](jsonStringMsg)
        assert(testByteMsg.sameElements(unpackedMsg("message")))
      }
    }

    "when sent LegacyPackMsg command" - {
      "should respond with PackedMsg be successfully unpacked via LegacyUnpackMsg" in {
        val senderVerKey = Some(createKeyInfo())
        val recipVerKeys = createKeyInfo()
        walletActor ! LegacyPackMsg(testByteMsg, Set(recipVerKeys), senderVerKey)
        val packedMsg = expectMsgType[PackedMsg]
        walletActor ! LegacyUnpackMsg(packedMsg.msg, fromVerKey = Some(recipVerKeys), isAnonCryptedMsg = false)
        val unpackedMsg = expectMsgType[UnpackedMsg]
        assert(testByteMsg.sameElements(unpackedMsg.msg))
      }
    }

    "when sent CreateCredReq command" - {
      "should respond with CredReq in a json string" in {
        walletActor ! CreateNewKey()
        val keys = expectMsgType[NewKeyCreated]

        walletActor ! CreateMasterSecret("masterSecretId")
        val masterSecretId = expectMsgType[String]

        val schema = Anoncreds.issuerCreateSchema(keys.did, "name", "version", seqToJson(Array("attr1", "attr2"))).get()
        walletActor ! CreateCredDef(issuerDID = keys.did,
          schemaJson = schema.getSchemaJson,
          tag = "tag",
          sigType = Some("CL"),
          revocationDetails = Some(RevocationDetails(false, "tails_file", 5).toString))
        val createdCredDef = expectMsgType[CreatedCredDef](60.second)

        walletActor ! CreateCredOffer(createdCredDef.credDefId)
        val createCredOffer = expectMsgType[String]

        walletActor ! CreateCredReq(createdCredDef.credDefId, proverDID = keys.did,
          createdCredDef.credDefJson, createCredOffer, masterSecretId)
        val credReqJson = expectMsgType[String]

      }
    }


    "when sent CreateCred command" - {
      "should respond with a created credential" ignore {
        walletActor ! CreateCred("credOfferJson", "credReqJson", "credValuesJson",
          "revRegistryId", -1)
        expectMsgType[String]
      }
    }


    "when sent CredForProofReq command" - {
      "should respond with a credential for the proof req" ignore  {
        walletActor ! CredForProofReq("proofRequest")
        expectMsgType[String]
      }
    }

    "when sent CreateProof command" - {
      "should respond with a proof" ignore {
        walletActor ! CreateProof("proofRequest", "usedCredentials", "schemas",
          "credentialDefs", "revStates", "masterSecret")
        expectMsgType[String]
      }
    }
  }


}
