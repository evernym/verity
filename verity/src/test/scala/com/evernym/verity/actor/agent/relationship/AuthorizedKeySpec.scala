package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, CLOUD_AGENT_KEY, EDGE_AGENT_KEY}
import com.evernym.verity.testkit.BasicSpec

class AuthorizedKeySpec extends BasicSpec {

  "AuthorizedKey" - {

    "when tried to construct without tags" - {
      "should be constructed successfully" in {
        AuthorizedKey("keyId", "", Set.empty)
        AuthorizedKey("keyId", "verKey", Set.empty)
      }
    }

    "when tried to construct with tags" - {
      "should be constructed successfully" in {
        AuthorizedKey("keyId", "", Set(AGENT_KEY_TAG))
        AuthorizedKey("keyId", "verKey", Set(AGENT_KEY_TAG))
      }
    }

    "when exercised interface members" - {
      "should behave correctly" in {
        val lak = AuthorizedKey("keyId", "", Set(AGENT_KEY_TAG))
        lak.keyId shouldBe "keyId"
        lak.tags shouldBe Set(AGENT_KEY_TAG)
        intercept[UnsupportedOperationException] {
          lak.verKey
        }

        val ak = AuthorizedKey("keyId", "verKey", Set(AGENT_KEY_TAG))
        ak.keyId shouldBe "keyId"
        ak.verKey shouldBe "verKey"
        ak.tags shouldBe Set(AGENT_KEY_TAG)
      }
    }

    "when tried to add tags" - {
      "should add tags successfully" in {
        val ak1 = AuthorizedKey("keyId", "verKey", Set(AGENT_KEY_TAG))
        val ak2 = ak1.addAllTags(Set(CLOUD_AGENT_KEY))
        ak2.tags shouldBe Set(AGENT_KEY_TAG, CLOUD_AGENT_KEY)

        val lak1 = AuthorizedKey("keyId", "", Set(AGENT_KEY_TAG))
        val lak2 = lak1.addAllTags(Set(CLOUD_AGENT_KEY))
        lak2.tags shouldBe Set(AGENT_KEY_TAG, CLOUD_AGENT_KEY)
      }
    }
  }

  "AuthorizedKeys" - {

    "when tried to construct with duplicate keys" - {
      "should fail with appropriate error" in {
        val ex1 = intercept[RuntimeException] {
          AuthorizedKeys(Seq(
            AuthorizedKey("keyId1", "verKey1", Set(AGENT_KEY_TAG)),
            AuthorizedKey("keyId2", "verKey1", Set(AGENT_KEY_TAG))
          ))
        }
        ex1.getMessage shouldBe "duplicate auth keys not allowed"

        val ex2 = intercept[RuntimeException] {
          AuthorizedKeys(Seq(
            AuthorizedKey("keyId1", "", Set(AGENT_KEY_TAG)),
            AuthorizedKey("keyId1", "verKey1", Set(AGENT_KEY_TAG))
          ))
        }
        ex2.getMessage shouldBe "duplicate auth keys not allowed"
      }
    }

    "when tried to filter by tags" - {
      "should produce correct result" in {
        val authKeys = AuthorizedKeys(Seq(
          AuthorizedKey("keyId1", "verKey1", Set(AGENT_KEY_TAG)),
          AuthorizedKey("keyId2", "verKey2", Set(EDGE_AGENT_KEY)),
          AuthorizedKey("keyId3", "verKey3", Set(CLOUD_AGENT_KEY))
        ))
        authKeys.filterByTags(AGENT_KEY_TAG) shouldBe Vector(AuthorizedKey("keyId1", "verKey1", Set(AGENT_KEY_TAG)))
        authKeys.filterByTags(EDGE_AGENT_KEY) shouldBe Vector(AuthorizedKey("keyId2", "verKey2", Set(EDGE_AGENT_KEY)))
        authKeys.filterByTags(CLOUD_AGENT_KEY) shouldBe Vector(AuthorizedKey("keyId3", "verKey3", Set(CLOUD_AGENT_KEY)))

        authKeys.filterByTags(AGENT_KEY_TAG, CLOUD_AGENT_KEY) shouldBe
          Vector(AuthorizedKey("keyId1", "verKey1", Set(AGENT_KEY_TAG)), AuthorizedKey("keyId3", "verKey3", Set(CLOUD_AGENT_KEY)))
      }
    }

    "when tried to filter by ver keys" - {
      "should produce correct result" in {
        val authKeys = AuthorizedKeys(Seq(
          AuthorizedKey("keyId1", "verKey1", Set(AGENT_KEY_TAG)),
          AuthorizedKey("keyId2", "verKey2", Set(EDGE_AGENT_KEY)),
          AuthorizedKey("keyId3", "verKey3", Set(CLOUD_AGENT_KEY))
        ))
        authKeys.filterByVerKeys("verKey1") shouldBe Vector(AuthorizedKey("keyId1", "verKey1", Set(AGENT_KEY_TAG)))
        authKeys.filterByVerKeys("verKey2") shouldBe Vector(AuthorizedKey("keyId2", "verKey2", Set(EDGE_AGENT_KEY)))
        authKeys.filterByVerKeys("verKey3") shouldBe Vector(AuthorizedKey("keyId3", "verKey3", Set(CLOUD_AGENT_KEY)))

        authKeys.filterByVerKeys("verKey1", "verKey3") shouldBe
          Vector(AuthorizedKey("keyId1", "verKey1", Set(AGENT_KEY_TAG)), AuthorizedKey("keyId3", "verKey3", Set(CLOUD_AGENT_KEY)))
      }
    }
  }
}
