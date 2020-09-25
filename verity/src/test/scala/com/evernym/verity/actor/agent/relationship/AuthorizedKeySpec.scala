package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.tags.{AgentKeyTag, CloudAgentKeyTag, EdgeAgentKeyTag}
import com.evernym.verity.testkit.BasicSpec

class AuthorizedKeySpec extends BasicSpec {

  "AuthorizedKey" - {

    "when tried to construct without tags" - {
      "should be constructed successfully" in {
        LegacyAuthorizedKey("keyId", Set.empty)
        AuthorizedKey("keyId", "verKey", Set.empty)
      }
    }

    "when tried to construct with tags" - {
      "should be constructed successfully" in {
        LegacyAuthorizedKey("keyId", Set(AgentKeyTag))
        AuthorizedKey("keyId", "verKey", Set(AgentKeyTag))
      }
    }

    "when exercised interface members" - {
      "should behave correctly" in {
        val lak = LegacyAuthorizedKey("keyId", Set(AgentKeyTag))
        lak.keyId shouldBe "keyId"
        lak.tags shouldBe Set(AgentKeyTag)
        intercept[UnsupportedOperationException] {
          lak.verKey
        }

        val ak = AuthorizedKey("keyId", "verKey", Set(AgentKeyTag))
        ak.keyId shouldBe "keyId"
        ak.verKey shouldBe "verKey"
        ak.tags shouldBe Set(AgentKeyTag)
      }
    }

    "when tried to add tags" - {
      "should add tags successfully" in {
        val ak1 = AuthorizedKey("keyId", "verKey", Set(AgentKeyTag))
        val ak2 = ak1.addTags(Set(CloudAgentKeyTag))
        ak2.tags shouldBe Set(AgentKeyTag, CloudAgentKeyTag)

        val lak1 = LegacyAuthorizedKey("keyId", Set(AgentKeyTag))
        val lak2 = lak1.addTags(Set(CloudAgentKeyTag))
        lak2.tags shouldBe Set(AgentKeyTag, CloudAgentKeyTag)
      }
    }
  }

  "AuthorizedKeys" - {

    "when tried to construct with duplicate keys" - {
      "should fail with appropriate error" in {
        val ex1 = intercept[RuntimeException] {
          AuthorizedKeys(
            AuthorizedKey("keyId1", "verKey1", Set(AgentKeyTag)),
            AuthorizedKey("keyId2", "verKey1", Set(AgentKeyTag))
          )
        }
        ex1.getMessage shouldBe "duplicate auth keys not allowed"

        val ex2 = intercept[RuntimeException] {
          AuthorizedKeys(
            LegacyAuthorizedKey("keyId1", Set(AgentKeyTag)),
            AuthorizedKey("keyId1", "verKey1", Set(AgentKeyTag))
          )
        }
        ex2.getMessage shouldBe "duplicate auth keys not allowed"
      }
    }

    "when tried to filter by tags" - {
      "should produce correct result" in {
        val authKeys = AuthorizedKeys(
          AuthorizedKey("keyId1", "verKey1", Set(AgentKeyTag)),
          AuthorizedKey("keyId2", "verKey2", Set(EdgeAgentKeyTag)),
          AuthorizedKey("keyId3", "verKey3", Set(CloudAgentKeyTag))
        )
        authKeys.filterByTags(AgentKeyTag) shouldBe Vector(AuthorizedKey("keyId1", "verKey1", Set(AgentKeyTag)))
        authKeys.filterByTags(EdgeAgentKeyTag) shouldBe Vector(AuthorizedKey("keyId2", "verKey2", Set(EdgeAgentKeyTag)))
        authKeys.filterByTags(CloudAgentKeyTag) shouldBe Vector(AuthorizedKey("keyId3", "verKey3", Set(CloudAgentKeyTag)))

        authKeys.filterByTags(AgentKeyTag, CloudAgentKeyTag) shouldBe
          Vector(AuthorizedKey("keyId1", "verKey1", Set(AgentKeyTag)), AuthorizedKey("keyId3", "verKey3", Set(CloudAgentKeyTag)))
      }
    }

    "when tried to filter by ver keys" - {
      "should produce correct result" in {
        val authKeys = AuthorizedKeys(
          AuthorizedKey("keyId1", "verKey1", Set(AgentKeyTag)),
          AuthorizedKey("keyId2", "verKey2", Set(EdgeAgentKeyTag)),
          AuthorizedKey("keyId3", "verKey3", Set(CloudAgentKeyTag))
        )
        authKeys.filterByVerKeys("verKey1") shouldBe Vector(AuthorizedKey("keyId1", "verKey1", Set(AgentKeyTag)))
        authKeys.filterByVerKeys("verKey2") shouldBe Vector(AuthorizedKey("keyId2", "verKey2", Set(EdgeAgentKeyTag)))
        authKeys.filterByVerKeys("verKey3") shouldBe Vector(AuthorizedKey("keyId3", "verKey3", Set(CloudAgentKeyTag)))

        authKeys.filterByVerKeys("verKey1", "verKey3") shouldBe
          Vector(AuthorizedKey("keyId1", "verKey1", Set(AgentKeyTag)), AuthorizedKey("keyId3", "verKey3", Set(CloudAgentKeyTag)))
      }
    }
  }
}
