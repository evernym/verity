package com.evernym.verity.util

import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.agentmsg.msgcodec.jackson.JacksonMsgCodec.Document
import com.evernym.verity.agentmsg.msgfamily.pairwise.CreateKeyReqMsg_MFV_0_6
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil._
import com.evernym.verity.agentmsg.msgfamily.TypeDetail
import com.evernym.verity.protocol.engine.Constants._
import com.evernym.verity.protocol.engine.registry.{PinstIdResolution, ProtocolRegistry}
import com.evernym.verity.protocol.protocols.connecting.v_0_6.{ConnectingProtoDef => ConnectingProtoDef_V_0_6}
import com.evernym.verity.protocol.protocols.tictactoe.Board.X
import com.evernym.verity.protocol.protocols.tictactoe.TicTacToeMsgFamily.Move
import com.evernym.verity.testkit.BasicSpec



class AgentMsgUtilSpec extends BasicSpec {

  implicit val protoReg = ProtocolRegistry(ConnectingProtoDef_V_0_6 -> PinstIdResolution.DEPRECATED_V0_1)

  val createKeyJson = s"""{"forDID":"did1","forDIDVerKey":"didverkey1"}"""

  "NativeJsonConverterUtil" - {

    //TODO: the json which is getting created had `@type` field at the end
    //so, in expected json, if we put that `@type` field before, then it fails, should come back to this commented test case
//    "when called toJson method for 0.5 native msg (with `@type` at the front)" - {
//      "should be able to get json string" in {
//        val expectedJson = s"""{"@type":{"name":"$MSG_TYPE_CREATE_KEY","ver":"$MFV_0_5","fmt":null},"forDID":"did1","forDIDVerKey":"didverkey1"}"""
//        AgentMsgUtil.toJson(CreateKeyReqMsg_MFV_0_5(TypeDetail(MSG_TYPE_CREATE_KEY, MFV_0_5), "did1", "didverkey1")) shouldBe expectedJson
//      }
//    }

    "when called toJson method for 0.5 native msg (with `@type` at the end)" - {
      "should be able to get json string" in {
        DefaultMsgCodec.toJson(CreateKeyReqMsg_MFV_0_6("did1", "didverkey1")) shouldBe createKeyJson
      }
    }

    "when called fromJson method for 0.5 agent msg" - {
      "should be able to get json string" in {
        val ckam = DefaultMsgCodec.fromJson[CreateKeyReqMsg_MFV_0_6](createKeyJson)
        ckam.forDID shouldBe "did1"
        ckam.forDIDVerKey shouldBe "didverkey1"
      }
    }

    "when called toJson method for 0.6 native msg (with `@type` at the end)" - {
      "should be able to get json string" in {
        val expectedJson = s"""{"forDID":"did1","forDIDVerKey":"didverkey1"}"""
        DefaultMsgCodec.toJson(CreateKeyReqMsg_MFV_0_6("did1", "didverkey1")) shouldBe expectedJson
      }
    }

    "when called fromJson method for 0.6 agent msg" - {
      "should be able to get json string" in {
        val jsonStr = s"""{"@type":"did:sov:123456;spec/$MSG_FAMILY_CONNECTING/$MFV_0_6/$MSG_TYPE_CREATE_KEY","forDID":"3ksdisdak4","forDIDVerKey":"49dkdkr0r"}"""
        val ckam = DefaultMsgCodec.fromJson[CreateKeyReqMsg_MFV_0_6](jsonStr)
        ckam.forDID shouldBe "3ksdisdak4"
        ckam.forDIDVerKey shouldBe "49dkdkr0r"
      }
    }

    "when called fromJson method with missing type detail" - {
      "should throw error" in {
        intercept[RuntimeException] {
          val jsonStr = s"""{"@type":"did:123456;spec/$MSG_FAMILY_CONNECTING/$MFV_0_6/$MSG_TYPE_CREATE_KEY","forDID":"3ksdisdak4","forDIDVerKey":"49dkdkr0r"}"""
          DefaultMsgCodec.fromJson(jsonStr)
        }
      }
    }

    "when deserializing TicTacToe move message into native class" - {
      "should be able to successfully do it" in {
        val jsonStr = """{"cv":"X", "at":"c3"}"""
        DefaultMsgCodec.fromJson[Move](jsonStr) shouldBe Move(X, "c3")
      }
    }

    "when using toDocument" - {
      "should respond with Document" in {
        val jsonStr = """{"cv":"X", "at":"c3"}"""
        DefaultMsgCodec.docFromStr(jsonStr).isInstanceOf[Document] shouldBe true
      }
    }

  }

}
