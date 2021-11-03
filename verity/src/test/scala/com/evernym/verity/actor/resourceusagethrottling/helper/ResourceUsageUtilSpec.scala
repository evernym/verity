package com.evernym.verity.actor.resourceusagethrottling.helper

import com.evernym.verity.agentmsg.msgpacker.MsgFamilyDetail
import com.evernym.verity.did.didcomm.v1.messages.MsgFamily.EvernymQualifier
import com.evernym.verity.did.didcomm.v1.messages.MsgType
import com.evernym.verity.testkit.BasicSpec

class ResourceUsageUtilSpec extends BasicSpec {

  "ResourceUsageUtil" - {

    "when called get CreateMsgReq message name" - {
      "should return given message name prefixed with 'CREATE_MSG_'" in {
        ResourceUsageUtil.getCreateMsgReqMsgName("credOffer") shouldBe "CREATE_MSG_credOffer"
      }
    }

    "when called get message resource name" - {

      "for MsgType" - {

        "if family name is actually family version" - {
          "result should contain message name only" in {
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, "0.5", "0.5", "CREATE_KEY")) shouldBe "CREATE_KEY"
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, "1.0.2", "1.0.2", "CREATE_KEY")) shouldBe "CREATE_KEY"
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, "1", "1", "CREATE_KEY")) shouldBe "CREATE_KEY"
          }
        }

        "if family name is real name" - {
          "result should contain family name and message name" in {
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, "relationship", "1.0", "create")) shouldBe "relationship/create"
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, "agent-provisioning", "0.7", "CONNECT")) shouldBe "agent-provisioning/CONNECT"
          }
        }

        "if family name is not real name and not family version" - {
          "result should contain family name and message name" in {
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, ".5", "0.5", "CREATE_KEY")) shouldBe ".5/CREATE_KEY"
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, "0.", "0.5", "CREATE_KEY")) shouldBe "0./CREATE_KEY"
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, ".", "0.5", "CREATE_KEY")) shouldBe "./CREATE_KEY"
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, "..", "0.5", "CREATE_KEY")) shouldBe "../CREATE_KEY"
            ResourceUsageUtil.getMessageResourceName(
              MsgType(EvernymQualifier, "0..5", "0.5", "CREATE_KEY")) shouldBe "0..5/CREATE_KEY"
          }
        }

      }

      "for MsgFamilyDetail" - {

        "if family name is actually family version" - {
          "result should contain message name only" in {
            ResourceUsageUtil.getMessageResourceName(
              MsgFamilyDetail(EvernymQualifier, "0.5", "0.5", "CREATE_KEY", None)) shouldBe "CREATE_KEY"
          }
        }

        "if family name is real name" - {
          "result should contain family name and message name" in {
            ResourceUsageUtil.getMessageResourceName(
              MsgFamilyDetail(EvernymQualifier, "relationship", "1.0", "create", None)) shouldBe "relationship/create"
          }
        }

        "if family name is not real name and not family version" - {
          "result should contain family name and message name" in {
            ResourceUsageUtil.getMessageResourceName(
              MsgFamilyDetail(EvernymQualifier, ".5", "0.5", "CREATE_KEY", None)) shouldBe ".5/CREATE_KEY"
          }
        }

      }

    }

    "when called get create message resource name" - {

      "if family name is actually family version" - {
        "result should contain message name only" in {
          ResourceUsageUtil.getCreateMessageResourceName(
            MsgType(EvernymQualifier, "0.5", "0.5", "connReq")) shouldBe "CREATE_MSG_connReq"
        }
      }

      "if family name is real name" - {
        "result should contain family name and message name" in {
          ResourceUsageUtil.getCreateMessageResourceName(
            MsgType(EvernymQualifier, "connecting", "0.5", "connReq")) shouldBe "connecting/CREATE_MSG_connReq"
        }
      }

      "if family name is not real name and not family version" - {
        "result should contain family name and message name" in {
          ResourceUsageUtil.getCreateMessageResourceName(
            MsgType(EvernymQualifier, ".5", "0.5", "connReq")) shouldBe ".5/CREATE_MSG_connReq"
        }
      }

    }

  }

}
