package com.evernym.verity.agentmsg

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.testkit.{CommonSpecUtil, TestAppConfig}
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, PackedMsg}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgTransformer, AgentMsgWrapper}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.ledger.LedgerPoolConnManager
import com.evernym.verity.libindy.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.libindy.wallet.LibIndyWalletProvider
import com.evernym.verity.protocol.engine.MsgFamilyVersion
import com.evernym.verity.testkit.BasicSpecWithIndyCleanup
import com.evernym.verity.vault._
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.testkit.util.TestUtil
import com.evernym.verity.vault.service.NonActorWalletService

trait AgentMsgSpecBase extends BasicSpecWithIndyCleanup with CommonSpecUtil {

  lazy val config:AppConfig = new TestAppConfig()
  lazy val poolConnManager: LedgerPoolConnManager =  new IndyLedgerPoolConnManager(config)
  lazy val walletProvider: LibIndyWalletProvider = new LibIndyWalletProvider(config)
  lazy val walletService = new NonActorWalletService(config, TestUtil, walletProvider, poolConnManager)
  lazy val walletAPI: WalletAPI = new WalletAPI(walletService, walletProvider)

  lazy val agentMsgTransformer: AgentMsgTransformer = new AgentMsgTransformer(walletAPI)

  def typ: String

  lazy val aliceWap: WalletAPIParam =
    createWallet(s"alice-$typ", walletAPI)
  lazy val aliceCloudAgencyAgentWap: WalletAPIParam =
    createWallet(s"alice-cloud-agency-$typ", walletAPI)
  lazy val aliceCloudAgentWap: WalletAPIParam =
    createWallet(s"alice-cloud-agent-$typ", walletAPI)

  lazy val aliceCloudAgentKeyInfo: KeyInfo = KeyInfo(Left(aliceCloudAgentKey.verKey))
  lazy val aliceKeyInfo: KeyInfo = KeyInfo(Left(aliceKey.verKey))

  lazy val aliceKey: NewKeyCreated = walletAPI.createNewKey(CreateNewKey())(aliceWap)
  lazy val aliceCloudAgencyKey: NewKeyCreated = walletAPI.createNewKey(CreateNewKey())(aliceCloudAgencyAgentWap)
  lazy val aliceCloudAgentKey: NewKeyCreated = walletAPI.createNewKey(CreateNewKey())(aliceCloudAgentWap)

  //TODO why does this need to be mutable? Tests need to be able to be run independently.
  var lastPackedMsg: PackedMsg = _



  def runSetupTests(): Unit = {
    "Alice" - {
      "when her cloud agency agent setup" - {
        "should be able to create its own key" taggedAs (UNSAFE_IgnoreLog) in {
          aliceCloudAgencyKey shouldBe a [NewKeyCreated]
        }
      }
      "when created her own key" - {
        "should be able to successfully create it" taggedAs (UNSAFE_IgnoreLog) in {
          aliceKey shouldBe a [NewKeyCreated]
        }
      }
      "when asked her cloud agency to create her agent" - {
        "should be able to successfully create it" taggedAs (UNSAFE_IgnoreLog) in {
          aliceCloudAgentKey shouldBe a [NewKeyCreated]
        }
      }
    }

  }

}


trait AgentTransformerSpec extends BasicSpecWithIndyCleanup
  with AgentMsgSpecBase {

  def msgPackFormat: MsgPackFormat
  def msgFamilyVersion: MsgFamilyVersion
  def msgClass: Class[_]
  def msg: Any

  def getEncryptParamFromAliceToAliceCloudAgent: EncryptParam = {
    val recipKeys = Set(aliceCloudAgentKeyInfo)
    val senderKeyOpt = Option(aliceKeyInfo)
    EncryptParam(recipKeys, senderKeyOpt)
  }

  def runPackTests(): Unit = {
    "Alice" - {
      "when tried to pack a msg for her cloud agent" - {
        "should be able to pack it" in {

          val jsonString = DefaultMsgCodec.toJson(msg)
          lastPackedMsg = agentMsgTransformer.pack(msgPackFormat,
            jsonString, getEncryptParamFromAliceToAliceCloudAgent)(aliceWap)
        }
      }
    }
  }

  //should only be called inside of tests
  lazy val unpacked: AgentMsgWrapper = agentMsgTransformer.unpack(lastPackedMsg.msg,
    KeyInfo(Left(aliceCloudAgentKey.verKey)))(aliceCloudAgentWap)

  def runUnpackTests(): Unit = {
    "Alice cloud agent" - {
      "when tried to unpack msg sent by Alice" - {
        "should be able to unpack it successfully" in {
          val msgType = unpacked.msgType
          unpacked.msgPackFormat shouldBe msgPackFormat
          msgType.familyName shouldBe MSG_FAMILY_AGENT_PROVISIONING
          msgType.familyVersion shouldBe msgFamilyVersion
          msgType.msgName shouldBe MSG_TYPE_CONNECT
          val unpackedMsg = DefaultMsgCodec.fromJson(unpacked.headAgentMsg.msg, msgClass)
          unpackedMsg shouldBe msg
        }
      }
    }
  }

}
