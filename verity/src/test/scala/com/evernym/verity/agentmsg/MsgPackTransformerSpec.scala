package com.evernym.verity.agentmsg

import com.evernym.verity.agentmsg.msgfamily.TypeDetail
import com.evernym.verity.protocol.engine.{MPV_MSG_PACK, MsgFamilyVersion, MsgPackVersion}
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.testkit.util.Connect_MFV_0_5


class MsgPackTransformerSpec extends AgentTransformerSpec {

  val typ = "v1"
  val msgPackVersion: MsgPackVersion = MPV_MSG_PACK
  val msgFamilyVersion: MsgFamilyVersion = MFV_0_5

  def msgClass: Class[Connect_MFV_0_5] = classOf[Connect_MFV_0_5]
  lazy val msg: Connect_MFV_0_5 = Connect_MFV_0_5(TypeDetail(MSG_TYPE_CONNECT, MTV_1_0), aliceKey.did, aliceKey.verKey)

  runSetupTests()
  runPackTests()
  runUnpackTests()

  "Alice cloud agent" - {
    "when tried to unpack msg sent by Alice" - {
      "should have msgVer 1.0" in {
        unpacked.headAgentMsg.msgFamilyDetail.msgVer.contains(MTV_1_0) shouldBe true
      }
    }
  }

}
