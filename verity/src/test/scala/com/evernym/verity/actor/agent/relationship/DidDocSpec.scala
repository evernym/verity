package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.testkit.BasicSpec
import Endpoints._
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, CLOUD_AGENT_KEY, EDGE_AGENT_KEY}

class DidDocSpec extends BasicSpec {

  "DidDoc" - {

    "when called different constructors" - {
      "should be successful" in {
        DidDoc("did1")
        DidDoc("did1", Some(AuthorizedKeys()), None)
        DidDoc("did1", Some(AuthorizedKeys(Seq(AuthorizedKey("key1", "", Set.empty)))))
        DidDoc(
          "did1",
          Some(AuthorizedKeys(Seq(AuthorizedKey("key1", "", Set.empty)))),
          Some(Endpoints.init(PushEndpoint("1", "push-token"), Set("key1")))
        )
      }
    }

    "when tried to add a new authorized key" - {
      "should get added to authorized keys" in {
        val dd = DidDoc("did1")
        val updatedDidDoc = dd.updatedWithNewAuthKey("key1", Set.empty)
        updatedDidDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key1", "", Set.empty)))
      }
    }

    "when tried to add new duplicate authorized key with different key id" - {
      "should replace existing key (if any) with newer one" in {
        val dd = DidDoc("did1")

        val dd1 = dd.updatedWithNewAuthKey("key1", "verKey1", Set(AGENT_KEY_TAG))
        dd1.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key1", "verKey1", Set(AGENT_KEY_TAG))))

        val ex = intercept[RuntimeException] {
          dd1.updatedWithNewAuthKey("key2", "verKey1", Set(EDGE_AGENT_KEY))
        }
        ex.getMessage shouldBe "duplicate auth keys not allowed"
      }
    }

    "when tried to add new duplicate legacy auth key (with same or different tags)" - {
      "should replace existing key (if any) with newer one" in {
        val dd = DidDoc("did1")

        val dd1 = dd.updatedWithNewAuthKey("key1", Set(AGENT_KEY_TAG))
        dd1.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key1", "", Set(AGENT_KEY_TAG))))

        val dd2 = dd1.updatedWithNewAuthKey("key1", Set(EDGE_AGENT_KEY))
        dd2.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key1", "", Set(EDGE_AGENT_KEY))))
      }
    }

    "when tried to add new duplicate auth key (with same or different tags)" - {
      "should replace existing key (if any) with newer one" in {
        val dd = DidDoc("did1")

        val dd1 = dd.updatedWithNewAuthKey("key1", "verKey1", Set(AGENT_KEY_TAG))
        dd1.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key1", "verKey1", Set(AGENT_KEY_TAG))))

        val dd2 = dd1.updatedWithNewAuthKey("key1", "verKey1", Set(EDGE_AGENT_KEY))
        dd2.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key1", "verKey1", Set(EDGE_AGENT_KEY))))
      }
    }

    "when tried to merge an existing authorized key (with same or different tags)" - {
      "should replace existing key (if any) with existing and newer tags" in {
        val dd = DidDoc("did1")

        val dd1 = dd.updatedWithNewAuthKey("key1", "verKey1", Set(AGENT_KEY_TAG))
        dd1.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key1", "verKey1", Set(AGENT_KEY_TAG))))

        val dd2 = dd1.updatedWithMergedAuthKey("key1", "verKey1", Set(CLOUD_AGENT_KEY))
        dd2.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key1", "verKey1", Set(AGENT_KEY_TAG, CLOUD_AGENT_KEY))))
      }
    }

    "when tried to merge an existing authorized key with new key id (with same or different tags)" - {
      "should replace existing key (if any) with existing and newer tags" in {
        val dd = DidDoc("did1")

        val dd1 = dd.updatedWithNewAuthKey("key2", "verKey1", Set(AGENT_KEY_TAG))
        dd1.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key2", "verKey1", Set(AGENT_KEY_TAG))))

        val dd2 = dd1.updatedWithMergedAuthKey("key1", "verKey1", Set(CLOUD_AGENT_KEY))
        dd2.authorizedKeys.value shouldBe AuthorizedKeys(Seq(AuthorizedKey("key2", "verKey1", Set(AGENT_KEY_TAG, CLOUD_AGENT_KEY))))
      }
    }

    "when tried to add a new endpoint without corresponding authorized key" - {
      "should throw an error" in {
        val dd = DidDoc("did1")
        val ex1 = intercept[RuntimeException] {
          dd.updatedWithEndpoint(PushEndpoint("1", "push-token-0"), Set("key1"))
        }
        ex1.getMessage shouldBe s"authorized key 'key1' doesn't exists"

        val ex2 = intercept[RuntimeException] {
          dd.updatedWithEndpoint(PushEndpoint("1", "push-token-0"), Set.empty)
        }
        ex2.getMessage shouldBe s"at least one auth key id require to be associated with the given endpoint"
      }
    }

    "when tried to add/update an endpoint" - {
      "should get added/updated" in {
        val dd = DidDoc("did1")

        val dd1 = dd
          .updatedWithNewAuthKey("key1", Set.empty)
          .updatedWithNewAuthKey("key2", Set.empty)

        val dd2 = dd1.updatedWithEndpoint(PushEndpoint("1", "push-token-0"), Set("key1"))
        dd2.endpoints.value shouldBe Endpoints.init(PushEndpoint("1", "push-token-0"), Set("key1"))

        val dd3 = dd2.updatedWithEndpoint(PushEndpoint("1", "push-token-1"), Set("key1", "key2"))
        dd3.endpoints.value shouldBe Endpoints.init(PushEndpoint("1", "push-token-1"), Set("key1", "key2"))
      }
    }

    "when tried to remove an endpoint" - {
      "should be removed successfully" in {
        val dd = DidDoc("did1")

        val dd1 = dd.updatedWithNewAuthKey("key1", Set.empty)
        val dd2 = dd1.updatedWithEndpoint(PushEndpoint("1", "push-token-0"), Set("key1"))

        dd2.endpoints.value shouldBe Endpoints(Vector(PushEndpoint("1", "push-token-0")), Map("1" -> KeyIds(Set("key1"))))
        dd2.endpoints.value.endpoints.size shouldBe 1

        val dd3 = dd2.updatedWithRemovedEndpointById("1")
        dd3.endpoints.value.endpoints.size shouldBe 0
        dd3.endpoints.value.endpointsToAuthKeys.size shouldBe 0
      }
    }

    "when tried to add same endpoint" - {
      "should update an existing endpoint" in {
        val dd = DidDoc("did1")

        val dd1 = dd
          .updatedWithNewAuthKey("key1", Set.empty)
          .updatedWithNewAuthKey("key2", Set.empty)

        val dd2 = dd1.updatedWithEndpoint(HttpEndpoint("2", "http://localhost:6001"), Set("key1"))
        dd2.endpoints.value shouldBe Endpoints.init(HttpEndpoint("2", "http://localhost:6001"), Set("key1"))

        val dd3 = dd2.updatedWithEndpoint(HttpEndpoint("2", "http://localhost:6001"), Set("key1", "key2"))
        dd3.endpoints.value shouldBe Endpoints.init(HttpEndpoint("2", "http://localhost:6001"), Set("key1", "key2"))
      }
    }

    "when tried to add same endpoint with different id" - {
      "should update an existing endpoint" in {
        val dd = DidDoc("did1")

        val dd1 = dd
          .updatedWithNewAuthKey("key1", Set.empty)
          .updatedWithNewAuthKey("key2", Set.empty)

        val dd2 = dd1.updatedWithEndpoint(HttpEndpoint("2", "http://localhost:6001"), Set("key1"))
        dd2.endpoints.value shouldBe Endpoints.init(HttpEndpoint("2", "http://localhost:6001"), Set("key1"))

        val dd3 = dd2.updatedWithEndpoint(HttpEndpoint("3", "http://localhost:6001"), Set("key1", "key2"))
        dd3.endpoints.value shouldBe Endpoints.init(HttpEndpoint("2", "http://localhost:6001"), Set("key1", "key2"))
      }
    }

    "when checked if an auth key exists" - {
      "should give correct result" in {
        val dd = DidDoc("did1")
        dd.existsAuthedVerKey("verKey1") shouldBe false
        val dd1 = dd.updatedWithNewAuthKey("key1", "verKey1", Set.empty)
        dd1.existsAuthedVerKey("verKey1") shouldBe true
      }
    }
  }

}
