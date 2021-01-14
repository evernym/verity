package com.evernym.verity.agentmsg.msgfamily

import com.evernym.verity.protocol.engine.MsgFamily.{EVERNYM_QUALIFIER, VALID_MESSAGE_TYPE_REG_EX_DID, VALID_MESSAGE_TYPE_REG_EX_HTTP, typeStrFromMsgType}
import com.evernym.verity.protocol.engine.MsgType
import com.evernym.verity.testkit.BasicSpec


//noinspection TypeAnnotation
class AgentMsgTypeBuilderSpec extends BasicSpec {

  val testMsgTypes = Seq(
    //should reference the spec to determine edge case valid strings
    MsgType(EVERNYM_QUALIFIER, "TEST_FAMILY","TEST_VERSION","TEST_NAME"),
    MsgType(EVERNYM_QUALIFIER, "test-family","test-version","test-name"),
    MsgType(EVERNYM_QUALIFIER, "testFamily","testVersion","testName")
  )

  testMsgTypes map typeStrFromMsgType foreach {
    str => {
      if(str.contains("did")) {
        str should fullyMatch regex VALID_MESSAGE_TYPE_REG_EX_DID
      } else str should fullyMatch regex VALID_MESSAGE_TYPE_REG_EX_HTTP
    }
  }

}
