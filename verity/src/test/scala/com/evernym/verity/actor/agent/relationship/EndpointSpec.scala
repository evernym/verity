package com.evernym.verity.actor.agent.relationship

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

    "when tried to initialize routing service type of endpoints with same id" - {
      "should throw appropriate error" in {
        val ex = intercept[RuntimeException] {
          Endpoints.init(Seq(
            LegacyRoutingServiceEndpoint("agencyDID", "theirAgentKeyDID",
              "theirAgentVerKey", "theirAgentKeyDlgProofSignature"),
            RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2"),
              Seq("authKey1"))))
        }
        ex.getMessage shouldBe "endpoints with same 'id' not allowed"
      }
    }

    "when tried to initialize endpoints with same id" - {
      "should throw appropriate error" in {
        val ex = intercept[RuntimeException] {
          Endpoints.init(Seq(
            RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")),
            HttpEndpoint("0", "http://abc.xyz", Seq("authKey1"))
          ))
        }
        ex.getMessage shouldBe "endpoints with same 'id' not allowed"
      }
    }

    "when tried to initialize endpoints with same value" - {
      "should throw appropriate error" in {
        val ex = intercept[RuntimeException] {
          Endpoints.init(Vector(
            HttpEndpoint("1", "http://abc.xyz.com"),
            HttpEndpoint("2", "http://abc.xyz.com", Seq("authKey1"))
          ))
        }
        ex.getMessage shouldBe "endpoint with same 'value' not allowed"
      }
    }

    "when tried to add/remove distinct endpoint" - {
      "should be able to add/remove it successfully" in {
        val endpoints = Endpoints.init(Seq(HttpEndpoint("0", "http://abc.xyz.com")))
        val afterAdd = endpoints.upsert(HttpEndpoint("1", "http://def.xyz.com"))
        afterAdd.endpoints shouldBe Seq(
          EndpointADT(HttpEndpoint("0", "http://abc.xyz.com")),
          EndpointADT(HttpEndpoint("1", "http://def.xyz.com")),
        )
        val afterRemoval = afterAdd.remove("0")
        afterRemoval.endpoints shouldBe Seq(
          EndpointADT(HttpEndpoint("1", "http://def.xyz.com")),
        )
      }
    }

    "when tried to add endpoint with same value" - {
      "should be able to update existing endpoint" in {
        val endpoints = Endpoints.init(Vector(HttpEndpoint("0", "http://abc.xyz.com")))
        val updatedEndpoints = endpoints.upsert(HttpEndpoint("1", "http://abc.xyz.com"))
        updatedEndpoints shouldBe endpoints
      }
    }

    "when called filterByKeyIds function" - {
      "should return correct result" in {
        val ep = Endpoints.init(Vector(
          RoutingServiceEndpoint("http://agency.evernym.com", Seq("rk1"), Seq("key1", "key2")),
          HttpEndpoint("1", "http://abc.xyz.com", Seq("key1", "key2"))))
        ep.filterByKeyIds("key1") shouldBe
          Vector(
            RoutingServiceEndpoint("http://agency.evernym.com", Seq("rk1"), Seq("key1", "key2")),
            HttpEndpoint("1", "http://abc.xyz.com", Seq("key1", "key2"))
          ).map(EndpointADT.apply)
        ep.filterByKeyIds("key2") shouldBe
          Vector(
            RoutingServiceEndpoint("http://agency.evernym.com", Seq("rk1"), Seq("key1", "key2")),
            HttpEndpoint("1", "http://abc.xyz.com", Seq("key1", "key2"))
          ).map(EndpointADT.apply)
      }
    }

    "when called filterByTypes function" - {
      "should return correct result" in {
        val ep = Endpoints.init(Vector(
          RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")),
          PushEndpoint("2", "push-token")))
        ep.filterByTypes(EndpointType.PUSH) shouldBe Vector(EndpointADT(PushEndpoint("2", "push-token")))
      }
    }

    "when called filterByValues function" - {
      "should return correct result" in {
        val ep = Endpoints.init(Vector(
          RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")),
          PushEndpoint("2", "push-token")))
        ep.filterByValues("push-token") shouldBe Vector(EndpointADT(PushEndpoint("2", "push-token")))
      }
    }

    "when called findById function" - {
      "should return correct result" in {
        val ep = Endpoints.init(Vector(
          RoutingServiceEndpoint("http://xyz.com", Vector("key1", "key2")),
          PushEndpoint("2", "push-token")))
        ep.findById("1") shouldBe None
        ep.findById("2") shouldBe Some(EndpointADT(PushEndpoint("2", "push-token")))
      }
    }
  }
}
