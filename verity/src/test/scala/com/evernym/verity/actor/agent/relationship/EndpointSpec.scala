package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.agent.relationship.AuthorizedKeys.KeyId
import com.evernym.verity.testkit.BasicSpec

class EndpointSpec extends BasicSpec {

  "Endpoint" - {
    "can be of different types" in {
      val lrse = LegacyRoutingServiceEndpoint("agencyDID", "theirAgentKeyDID",
        "theirAgentVerKey", "theirAgentKeyDlgProofSignature")
      lrse.isOfType(EndpointType.ROUTING_SERVICE_ENDPOINT) shouldBe true

      val rse = RoutingServiceEndpoint("http://their.xyz.com", Vector("key1", "key2"))
      rse.isOfType(EndpointType.ROUTING_SERVICE_ENDPOINT) shouldBe true

      val pe = PushEndpoint("2", "push-token")
      pe.isOfType(EndpointType.PUSH)

      val he = HttpEndpoint("3", "http://my.xyz.com")
      he.isOfType(EndpointType.HTTP)

      val fpe = ForwardPushEndpoint("4", "http://my.xyz.com")
      fpe.isOfType(EndpointType.FWD_PUSH)

      val spe = SponsorPushEndpoint("5", "http://my-sponsor.xyz.com")
      spe.isOfType(EndpointType.SPR_PUSH)
    }
  }

  "Endpoints" - {

    "when tried to add routing service type of endpoints with same id" - {
      "should throw appropriate error" in {
        val ex = intercept[RuntimeException] {
          Endpoints.init(Vector(
            LegacyRoutingServiceEndpoint("agencyDID", "theirAgentKeyDID",
              "theirAgentVerKey", "theirAgentKeyDlgProofSignature"),
            RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2"))),
            Set("authKey1"))
        }
        ex.getMessage shouldBe "endpoints with same 'id' not allowed"
      }
    }

    "when tried to add endpoints with same id" - {
      "should throw appropriate error" in {
        val ex = intercept[RuntimeException] {
          Endpoints.init(Vector(
            RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")),
            PushEndpoint("0", "push-token")),
            Set("authKey1")
          )
        }
        ex.getMessage shouldBe "endpoints with same 'id' not allowed"
      }
    }

    "when tried to add endpoints with same value" - {
      "should throw appropriate error" in {
        val ex = intercept[RuntimeException] {
          Endpoints.init(Vector(
            PushEndpoint("1", "push-token-1"),
            PushEndpoint("2", "push-token-1")),
            Set("authKey1")
          )
        }
        ex.getMessage shouldBe "endpoint with same 'value' not allowed"
      }
    }

    "when tried to add endpoints without proper auth key mapping" - {
      "should throw appropriate error" in {
        val ex = intercept[RuntimeException] {
          Endpoints(Vector(
            PushEndpoint("1", "push-token-1"),
            PushEndpoint("2", "push-token-2")),
            Map.empty
          )
        }
        ex.getMessage shouldBe "endpoints without auth key mapping not allowed"
      }
    }

    "when called filterByKeyIds function" - {
      "should return correct result" in {
        val ep = Endpoints.init(Vector(
          RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")),
          PushEndpoint("1", "push-token")),
          Set("key1", "key2"))
        ep.filterByKeyIds("key1") shouldBe Vector(RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")), PushEndpoint("1", "push-token"))
        ep.filterByKeyIds("key2") shouldBe Vector(RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")), PushEndpoint("1", "push-token"))
      }
    }

    "when called filterByTypes function" - {
      "should return correct result" in {
        val ep = Endpoints.init(Vector(
          RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")),
          PushEndpoint("2", "push-token")), Set.empty[KeyId])
        ep.filterByTypes(EndpointType.PUSH) shouldBe Vector(PushEndpoint("2", "push-token"))
      }
    }

    "when called filterByValues function" - {
      "should return correct result" in {
        val ep = Endpoints.init(Vector(
          RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")),
          PushEndpoint("2", "push-token")), Set.empty[KeyId])
        ep.filterByValues("push-token") shouldBe Vector(PushEndpoint("2", "push-token"))
      }
    }

    "when called findById function" - {
      "should return correct result" in {
        val ep = Endpoints.init(Vector(
          RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")),
          PushEndpoint("2", "push-token")), Set.empty[KeyId])
        ep.findById("1") shouldBe None
        ep.findById("2") shouldBe Option(PushEndpoint("2", "push-token"))
      }
    }
  }
}
