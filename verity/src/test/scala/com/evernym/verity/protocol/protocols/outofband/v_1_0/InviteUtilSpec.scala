package com.evernym.verity.protocol.protocols.outofband.v_1_0

import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.{IssueCredMsgFamily, IssueCredentialProtoDef}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class InviteUtilSpec extends AnyFreeSpec with Matchers{
  "buildThreadedInviteId" - {
    "should build encoded ID with valid input" in {
      val threadId = "b004c3d5-1bda-4f09-bf13-0e8157877459"
      val testVal = InviteUtil.buildThreadedInviteId(
        IssueCredentialProtoDef.protoRef,
        "MizBMD62xfh3wY17i36GHu",
        threadId)
      testVal shouldBe "9Rp6Jk3-HwcjgSeug-QNeF6Wijb-AqmUTYYxW-mfKio8eW7hGm5gWZY2yocYzpZJyG8fXJAsS5zLx5bf6egwkLpHPu7hMpF2nssmYWyiChJPHFWqVvZm95r"

      testVal.charAt(6) should not be('-')
      testVal.charAt(7) shouldBe '-'
      testVal.charAt(8) should not be('-')
    }
  }

  "isThreadedInviteId" - {
    "encoded ids should always return true" in {
      for(_ <- 1 to 1000) {
        val testVal = InviteUtil.buildThreadedInviteId(
          IssueCredentialProtoDef.protoRef,
          UUID.randomUUID().toString,
          UUID.randomUUID().toString
        )
        InviteUtil.isThreadedInviteId(testVal) shouldBe true
      }
    }
    "random UUID should always return false" in {
      for(_ <- 1 to 200000) {
        InviteUtil.isThreadedInviteId(UUID.randomUUID().toString) shouldBe false
      }
    }
  }

  "parseThreadedInviteId" - {
    "should parse above id" in {
      val threadedInviteId = "9Rp6Jk3-HwcjgSeug-QNeF6Wijb-AqmUTYYxW-mfKio8eW7hGm5gWZY2yocYzpZJyG8fXJAsS5zLx5bf6egwkLpHPu7hMpF2nssmYWyiChJPHFWqVvZm95r"
      val threadedInviteIdObj = InviteUtil.parseThreadedInviteId(threadedInviteId).get
      threadedInviteIdObj.protoRefStr shouldBe IssueCredentialProtoDef.protoRef.toString
      threadedInviteIdObj.relationshipId shouldBe "MizBMD62xfh3wY17i36GHu"
      threadedInviteIdObj.threadId shouldBe "b004c3d5-1bda-4f09-bf13-0e8157877459"
    }
  }

  "simulate round trips" - {
    "should build and parse random ids" in {
      for(_ <- 1 to 1000) {
        val rel = UUID.randomUUID().toString
        val tid = UUID.randomUUID().toString
        val testVal = InviteUtil.buildThreadedInviteId(
          IssueCredentialProtoDef.protoRef,
          rel,
          tid
        )

        val testObj = InviteUtil.parseThreadedInviteId(testVal).get
        testObj.protoRefStr shouldBe IssueCredentialProtoDef.protoRef.toString
        testObj.relationshipId shouldBe rel
        testObj.threadId shouldBe tid

      }
    }
  }

}
