package com.evernym.verity.protocol.protocols.issueCredential.v_1_0

import com.evernym.verity.constants.Constants.UNKNOWN_OTHER_ID
import com.evernym.verity.constants.InitParamConstants.{OTHER_ID, SELF_ID}
import com.evernym.verity.protocol.engine.context.Roster
import com.evernym.verity.protocol.engine.{Parameter, Parameters}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.IssueCredential._
import com.evernym.verity.protocol.protocols.issueCredential.v_1_0.Role.{Holder, Issuer}
import org.scalatest.OptionValues

class IssueCredentialNonProtocolSpec extends AnyFreeSpec with Matchers with OptionValues{
  "CredentialProtocol" - {
    "buildInitialized" in {
      val testSet = Set(
        Parameter(SELF_ID, "1"),
        Parameter(OTHER_ID, UNKNOWN_OTHER_ID)
      )

      buildInitialized(Parameters(testSet)).params should have length(1)
    }
    "setSenderRole" - {
      "with pre-set selfId and otherId" in {
        val startRoster:Roster[Role] = Roster()
          .withParticipant("self_id", isSelf = true)
          .withParticipant("other_id")

        val testRoster = setSenderRole("other_id", Holder(), startRoster)

        testRoster.selfId.value shouldBe "self_id"
        testRoster.selfRole.value shouldBe Issuer()
        testRoster.otherId() shouldBe "other_id"
        testRoster.roleForId(testRoster.otherId()).value shouldBe Holder()
      }
      "with only pre-set selfId" in {
        val startRoster:Roster[Role] = Roster()
          .withParticipant("self_id", isSelf = true)

        val testRoster = setSenderRole("other_id", Holder(), startRoster)

        testRoster.selfId.value shouldBe "self_id"
        testRoster.selfRole.value shouldBe Issuer()
        testRoster.otherId() shouldBe "other_id"
        testRoster.roleForId(testRoster.otherId()).value shouldBe Holder()
      }

      "with only pre-set selfId and role set" in {
        val startRoster:Roster[Role] = Roster()
          .withParticipant("self_id", isSelf = true)
          .withSelfAssignment(Issuer())

        val testRoster = setSenderRole("other_id", Holder(), startRoster)

        testRoster.selfId.value shouldBe "self_id"
        testRoster.selfRole.value shouldBe Issuer()
        testRoster.otherId() shouldBe "other_id"
        testRoster.roleForId(testRoster.otherId()).value shouldBe Holder()
      }
    }
  }
}
