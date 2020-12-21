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
import com.evernym.verity.vault.{GetVerKeyByDIDParam, KeyInfo}
import org.hyperledger.indy.sdk.anoncreds.AnoncredsResults.IssuerCreateAndStoreCredentialDefResult
class WalletActorSpec extends TestKitBase
  with ProvidesMockPlatform
  with BasicSpec
  with ImplicitSender
  with Eventually {

  implicit lazy val system: ActorSystem = AkkaTestBasic.system()
  lazy val walletActorEntityId: String = UUID.randomUUID().toString
  lazy val walletActor: agentRegion = agentRegion(walletActorEntityId, walletRegionActor)
  val testDID = "did:sov:NcysrVCeLU1WNdJdLYxU6g"
  val keyInfo = KeyInfo(Right(GetVerKeyByDIDParam(testDID, getKeyFromPool = false)))

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
        expectMsgType[NewKeyCreated]
      }
    }

    "when sent SignLedgerRequest command" - {
      "should respond with LedgerRequest" in {
        walletActor ! SignLedgerRequest(LedgerRequest("{}"), Submitter.apply())
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

    "when sent SignMsg command" - {
      "should respond with TheirKeyCreated" in {
        walletActor ! SignMsg(keyInfo, Array())
        expectMsgType[Array[Byte]]
      }
    }

    "when sent VerifySigByKeyInfo command" - {
      "should respond with VerifySigResult" in {
        walletActor ! VerifySigByKeyInfo(keyInfo, challenge = Array(), signature = Array())
        expectMsgType[VerifySigResult]
      }
    }

    "when sent PackMsg command" - {
      "should respond with PackedMsg" in {
        walletActor ! PackMsg(msg = Array(), recipVerKeys = Set(keyInfo), senderVerKey = Some(keyInfo))
        expectMsgType[PackedMsg]
      }
    }

    "when sent UnpackMsg command" - {
      "should respond with UnpackedMsg" in {
        walletActor ! UnpackMsg(Array())
        expectMsgType[UnpackedMsg]
      }
    }

    "when sent LegacyPackMsg command" - {
      "should respond with PackedMsg" in {
        walletActor ! LegacyPackMsg(msg = Array(), recipVerKeys = Set(keyInfo), senderVerKey = Some(keyInfo))
        expectMsgType[PackedMsg]
      }
    }

    "when sent LegacyUnpackMsg command" - {
      "should respond with UnpackedMsg" in {
        walletActor ! LegacyUnpackMsg(msg = Array(), fromVerKey = Some(keyInfo), isAnonCryptedMsg = false)
        expectMsgType[UnpackedMsg]
      }
    }

    "when sent CreateMasterSecret command" - {
      "should respond with TheirKeyCreated" in {
        walletActor ! CreateMasterSecret("masterSecretId")
        expectMsgType[String]
      }
    }

    "when sent CreateCredDef command" - {
      "should respond with IssuerCreateAndStoreCredentialDefResult" in {
        walletActor ! CreateCredDef(issuerDID = testDID,
          schemaJson = "{}",
          tag = String,
          sigType = None,
          revocationDetails = Some("{}"))
        expectMsgType[IssuerCreateAndStoreCredentialDefResult]
      }
    }


    "when sent CreateCredOffer command" - {
      "should respond with TheirKeyCreated" in {
        walletActor ! CreateCredOffer("credDefId")
        expectMsgType[String]
      }
    }


    "when sent CreateCredReq command" - {
      "should respond with TheirKeyCreated" in {
        walletActor ! CreateCredReq("credDefId", proverDID = testDID,
          "credDefJson", "credOfferJson", "masterSecretId")
        expectMsgType[String]
      }
    }


    "when sent CreateCred command" - {
      "should respond with TheirKeyCreated" in {
        walletActor ! CreateCred("credOfferJson", "credReqJson", "credValuesJson",
          "revRegistryId", -1)
        expectMsgType[String]
      }
    }


    "when sent CredForProofReq command" - {
      "should respond with TheirKeyCreated" in {
        walletActor ! CredForProofReq("proofRequest")
        expectMsgType[String]
      }
    }

    "when sent CreateProof command" - {
      "should respond with TheirKeyCreated" in {
        walletActor ! CreateProof("proofRequest", "usedCredentials", "schemas",
          "credentialDefs", "revStates", "masterSecret")
        expectMsgType[String]
      }
    }
  }


 }
