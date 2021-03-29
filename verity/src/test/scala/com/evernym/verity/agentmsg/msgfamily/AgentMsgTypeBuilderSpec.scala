package com.evernym.verity.agentmsg.msgfamily

import com.evernym.verity.protocol.engine.MsgFamily.{COMMUNITY_QUALIFIER, EVERNYM_QUALIFIER, QUALIFIER_FORMAT_HTTP, VALID_MESSAGE_TYPE_REG_EX_DID, VALID_MESSAGE_TYPE_REG_EX_HTTP, msgQualifierFromQualifierStr, qualifierStrFromMsgQualifier, typeStrFromMsgType}
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
      if(QUALIFIER_FORMAT_HTTP) {
        str should fullyMatch regex VALID_MESSAGE_TYPE_REG_EX_HTTP
      } else str should fullyMatch regex VALID_MESSAGE_TYPE_REG_EX_DID
    }
  }

  val testCommunityQualifierStr = qualifierStrFromMsgQualifier(COMMUNITY_QUALIFIER)
  val testEvernymQualifierStr = qualifierStrFromMsgQualifier(EVERNYM_QUALIFIER)

  if(QUALIFIER_FORMAT_HTTP) {
    testCommunityQualifierStr shouldBe "didcomm.org"
    testEvernymQualifierStr shouldBe "didcomm.evernym.com"
  } else {
    testCommunityQualifierStr shouldBe "BzCbsNYhMrjHiqZDTUASHg"
    testEvernymQualifierStr shouldBe "123456789abcdefghi1234"
  }

  msgQualifierFromQualifierStr("didcomm.org") shouldBe COMMUNITY_QUALIFIER
  msgQualifierFromQualifierStr("didcomm.evernym.com") shouldBe EVERNYM_QUALIFIER

}
