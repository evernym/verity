package com.evernym.verity.actor

import java.util.UUID

import akka.actor.{ActorSystem, PoisonPill}
import akka.testkit.{ImplicitSender, TestKitBase}
import com.evernym.verity.actor.testkit.AkkaTestBasic
import com.evernym.verity.actor.testkit.actor.ProvidesMockPlatform
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import com.evernym.verity.actor.wallet._
import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet}
import com.evernym.verity.ledger.{LedgerRequest, Submitter}
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.engine.WalletAccess.KEY_ED25519
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyInfo, WalletAPIParam}
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults.IssuerCreateAndStoreCredentialDefResult
import org.hyperledger.indy.sdk.ledger.Ledger.buildGetNymRequest
class WalletActorSpec extends TestKitBase
  with ProvidesMockPlatform
  with BasicSpec
  with ImplicitSender
  with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
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

    "when sent CreateNewKey command" - {
      "should respond with NewKeyCreated" in {
        walletActor ! CreateNewKey()
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent PoisonPill command" - {
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

    "when sent CreateNewKey command again" - {
      "should respond with NewKeyCreated" in {
        walletActor ! CreateNewKey()
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
      "should respond with TheirKeyCreated" in {
        walletActor ! StoreTheirKey(testDID, "CnToPx3rPHNaXkMMtdPTnsK45pSHvP1e4BzNrk3oSVgr")
        expectMsgType[TheirKeyCreated]
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
        val unpackedMsg = expectMsgType[UnpackedMsg]
        assert(testByteMsg.sameElements(unpackedMsg.msg))
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

    "when sent CreateMasterSecret command" - {
      "should respond with TheirKeyCreated" in {
        walletActor ! CreateMasterSecret("masterSecretId")
        expectMsgType[String]
      }
    }
  }
 }
