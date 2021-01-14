package com.evernym.verity.protocol.engine.roster

import com.evernym.verity.protocol.engine.Roster
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class RosterSpec extends AnyFreeSpec with Matchers {
  "Roster" - {
    "should allow change of the self ParticipantId" in {
      var r = Roster().withParticipant("TEST1", true)
      r.selfId_! shouldBe "TEST1"

      r = r.changeSelfId("TEST2")
      r.selfId_! shouldBe "TEST2"

      r.changeSelfId("TEST3").selfId_! shouldBe "TEST3"
    }

    "should allow change of the other ParticipantId" in {
      var r = Roster()
        .withParticipant("TEST1", true)
        .withParticipant("OTHER1")
      r.otherId() shouldBe "OTHER1"

      r = r.changeOtherId("OTHER2")
      r.otherId() shouldBe "OTHER2"
    }
  }

}
