package com.evernym.verity.agentmsg

import com.evernym.verity.actor.agent.MsgPackFormat
import com.evernym.verity.actor.agent.MsgPackFormat.MPF_INDY_PACK
import com.evernym.verity.agentmsg.msgfamily.TypeDetail
import com.evernym.verity.testkit.agentmsg.AgentMsgHelper._
import com.evernym.verity.agentmsg.msgpacker.AgentMsgWrapper
import com.evernym.verity.vault._
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.MsgFamilyVersion
import com.evernym.verity.testkit.util.{Connect_MFV_0_5, Connect_MFV_0_6}


class IndyPackTransformerSpec extends AgentTransformerSpec {

  val typ = "v2"
  val msgPackFormat: MsgPackFormat = MPF_INDY_PACK
  val msgFamilyVersion: MsgFamilyVersion = MFV_0_6

  def msgClass: Class[Connect_MFV_0_6] = classOf[Connect_MFV_0_6]
  lazy val msg = Connect_MFV_0_6(MSG_TYPE_DETAIL_CONNECT, aliceKey.did, aliceKey.verKey)

  lazy val testMsg_0_5 = Connect_MFV_0_5(TypeDetail(MSG_TYPE_CONNECT, "1.0"), aliceKey.did, aliceKey.verKey)

  runSetupTests()

  runPackTests()

  runUnpackTests()

  "Alice cloud agent" - {
    "when tried to deserialize it" - {
      "should be able to deserialize it successfully" in {
        val unpackedMsgWrapper = agentMsgTransformer.unpack(
          lastPackedMsg.msg, KeyParam(Left(aliceCloudAgentKey.verKey)))(aliceCloudAgentWap)
        unpackedMsgWrapper.headAgentMsg.msg shouldBe DefaultMsgCodec.toJson(msg)
      }
    }
  }

  "Alice cloud agent" - {
    "when tried to pack old agent msg with indy pack" - {
      "should be able to successfully do it" in {
        val jsonString = DefaultMsgCodec.toJson(testMsg_0_5)
        lastPackedMsg = agentMsgTransformer.pack(msgPackFormat,
          jsonString, getEncryptParamFromAliceToAliceCloudAgent)(aliceWap)
      }
    }

    "when tried to unpack it with indy pack" - {
      "should be able to successfully do it" in {
        lazy val unpacked: AgentMsgWrapper = agentMsgTransformer.unpack(lastPackedMsg.msg,
          KeyParam(Left(aliceCloudAgentKey.verKey)))(aliceCloudAgentWap)
        val msgType = unpacked.msgType
        unpacked.msgPackFormat shouldBe msgPackFormat
        msgType.familyName shouldBe MSG_FAMILY_AGENT_PROVISIONING
        msgType.familyVersion shouldBe MFV_0_5
        msgType.msgName shouldBe MSG_TYPE_CONNECT
        unpacked.headAgentMsg.convertTo[Connect_MFV_0_5] shouldBe testMsg_0_5
      }
    }
  }

}
