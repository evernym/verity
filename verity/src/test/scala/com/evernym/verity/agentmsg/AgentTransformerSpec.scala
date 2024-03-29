package com.evernym.verity.agentmsg

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.testkit.checks.UNSAFE_IgnoreLog
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated, PackedMsg}
import com.evernym.verity.agentmsg.msgpacker.{AgentMsgTransformer, AgentMsgWrapper}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.MsgFamilyVersion
import com.evernym.verity.testkit.{AwaitResult, BasicSpecWithIndyCleanup, HasTestWalletAPI}
import com.evernym.verity.vault._
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.util2.HasExecutionContextProvider

import scala.concurrent.ExecutionContext

trait AgentMsgSpecBase
  extends BasicSpecWithIndyCleanup
    with HasTestWalletAPI
    with CommonSpecUtil
    with HasExecutionContextProvider {

  private implicit lazy val executionContext: ExecutionContext = futureExecutionContext

  lazy val agentMsgTransformer: AgentMsgTransformer = new AgentMsgTransformer(testWalletAPI, testAppConfig, executionContext)

  def typ: String

  lazy val aliceWap: WalletAPIParam =
    createWallet(s"alice-$typ", testWalletAPI)
  lazy val aliceCloudAgencyAgentWap: WalletAPIParam =
    createWallet(s"alice-cloud-agency-$typ", testWalletAPI)
  lazy val aliceCloudAgentWap: WalletAPIParam =
    createWallet(s"alice-cloud-agent-$typ", testWalletAPI)

  lazy val aliceCloudAgentKeyParam: KeyParam = KeyParam(Left(aliceCloudAgentKey.verKey))
  lazy val aliceKeyParam: KeyParam = KeyParam(Left(aliceKey.verKey))

  lazy val aliceKey: NewKeyCreated = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey())(aliceWap)
  lazy val aliceCloudAgencyKey: NewKeyCreated = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey())(aliceCloudAgencyAgentWap)
  lazy val aliceCloudAgentKey: NewKeyCreated = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey())(aliceCloudAgentWap)

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


trait AgentTransformerSpec
  extends BasicSpecWithIndyCleanup
    with AgentMsgSpecBase
    with AwaitResult {

  override def appConfig: AppConfig = testAppConfig

  def msgPackFormat: MsgPackFormat
  def msgFamilyVersion: MsgFamilyVersion
  def msgClass: Class[_]
  def msg: Any

  def getEncryptParamFromAliceToAliceCloudAgent: EncryptParam = {
    val recipKeys = Set(aliceCloudAgentKeyParam)
    val senderKeyOpt = Option(aliceKeyParam)
    EncryptParam(recipKeys, senderKeyOpt)
  }

  def runPackTests(): Unit = {
    "Alice" - {
      "when tried to pack a msg for her cloud agent" - {
        "should be able to pack it" in {

          val jsonString = DefaultMsgCodec.toJson(msg)
          lastPackedMsg = convertToSyncReq(agentMsgTransformer.packAsync(msgPackFormat,
            jsonString, getEncryptParamFromAliceToAliceCloudAgent)(aliceWap))
        }
      }
    }
  }

  //should only be called inside of tests
  lazy val unpacked: AgentMsgWrapper = convertToSyncReq(agentMsgTransformer.unpackAsync(lastPackedMsg.msg,
    KeyParam(Left(aliceCloudAgentKey.verKey)))(aliceCloudAgentWap))

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
