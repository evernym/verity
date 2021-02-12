package com.evernym.verity.actor.agent.relationship

import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.testkit.{BasicAsyncSpec, CleansUpIndyClientFirst, HasTestWalletAPI}
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, CLOUD_AGENT_KEY, EDGE_AGENT_KEY}
import com.evernym.verity.actor.wallet.{CreateNewKey, NewKeyCreated}
import com.evernym.verity.protocol.protocols.connecting.common.LegacyRoutingDetail
import org.scalatest.OptionValues

class DidDocBuilderSpec
  extends BasicAsyncSpec
    with CleansUpIndyClientFirst
    with HasTestWalletAPI
    with OptionValues {

  override def createWallet = true
  val relDidPair: NewKeyCreated = {
    agentWalletId
    walletAPI.executeSync[NewKeyCreated](CreateNewKey())
  }
  val thisAgentKey: NewKeyCreated = walletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = Option("0000000000000000000000000000TEST")))
  val otherAgentKey: NewKeyCreated = walletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = Option("000000000000000000000000000OTHER")))
  val theirAgentKey: NewKeyCreated = walletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = Option("000000000000000000000000000THEIR")))          //key to represent their agent


  implicit lazy val didDocBuilderParam: DidDocBuilderParam =
    DidDocBuilderParam(new TestAppConfig(), Option(thisAgentKey.did))

  "DidDocBuilder" - {

    "when building DidDoc with auth keys with and without ver keys" - {
      "should create expected DidDic" in {
        val didDoc =
          DidDocBuilder()
            .withDid(relDidPair.did)
            .withAuthKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
            .withAuthKey(thisAgentKey.did, "", Set.empty)
            .didDoc

        didDoc.did shouldBe relDidPair.did
        didDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
          AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY)),
          AuthorizedKey(thisAgentKey.did, "", Set.empty),
        ))
        didDoc.endpoints.value shouldBe Endpoints.init(
          List(RoutingServiceEndpoint("localhost:9000/agency/msg", List.empty, List(thisAgentKey.did))))
      }
    }

    "when try to add duplicate auth key" - {
      "should throw appropriate exception" in {
        val ex1 = intercept[RuntimeException] {
          DidDocBuilder()
            .withDid(relDidPair.did)
            .withAuthKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
            .withAuthKey(relDidPair.verKey, relDidPair.verKey, Set(CLOUD_AGENT_KEY))
        }
        ex1.getMessage shouldBe "duplicate auth keys not allowed"

        val ex2 = intercept[RuntimeException] {
          DidDocBuilder()
            .withDid(relDidPair.did)
            .withAuthKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
            .withAuthKey(thisAgentKey.did, relDidPair.verKey, Set(CLOUD_AGENT_KEY))
        }
        ex2.getMessage shouldBe "duplicate auth keys not allowed"
      }
    }

    "when called 'updatedDidDocWithMigratedAuthKeys'" - {
      "should respond with updated did doc with proper auth ver keys" in {
        DidDocBuilder()
          .withDid(relDidPair.did)
          .withAuthKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
          .withAuthKey(thisAgentKey.did, "", Set.empty)
          .updatedDidDocWithMigratedAuthKeys(List.empty, agentWalletAPI)
          .map { didDoc =>
            didDoc.did shouldBe relDidPair.did
            didDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
              AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY)),
              AuthorizedKey(thisAgentKey.did, thisAgentKey.verKey, Set.empty),
            ))
            didDoc.endpoints.value shouldBe Endpoints.init(
              List(RoutingServiceEndpoint("localhost:9000/agency/msg", List.empty, List(thisAgentKey.did))))
          }
      }
    }

    "when called 'updatedDidDocWithMigratedAuthKeys' with key id as empty value" - {
      "should respond with updated did doc with proper auth ver keys" in {
        DidDocBuilder()
          .withDid(relDidPair.did)
          .withAuthKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
          .withAuthKey("", "", Set.empty)
          .updatedDidDocWithMigratedAuthKeys(List.empty, agentWalletAPI)
          .map { didDoc =>
            didDoc.did shouldBe relDidPair.did
            didDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
              AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY)),
              AuthorizedKey("", "", Set.empty),
            ))
          }
      }
    }

    "when called 'withAuthKeyAndEndpointDetail' for this agent's auth key and endpoint" - {
      "should update did doc as expected" in {
        val didDoc = DidDocBuilder()
          .withDid(relDidPair.did)
          .withAuthKey(relDidPair.did, relDidPair.verKey)
          .withAuthKeyAndEndpointDetail(
            thisAgentKey.did, thisAgentKey.verKey, Set(CLOUD_AGENT_KEY),
            Left(LegacyRoutingDetail("agencyDID", thisAgentKey.did, thisAgentKey.verKey, "signature")))
          .didDoc

        didDoc.authorizedKeys_!.keys shouldBe Seq(
          AuthorizedKey(relDidPair.did, relDidPair.verKey),
          AuthorizedKey(thisAgentKey.did, thisAgentKey.verKey, Set(CLOUD_AGENT_KEY))
        )

        didDoc.endpoints_!.endpoints shouldBe List(EndpointADT(
          LegacyRoutingServiceEndpoint("agencyDID", thisAgentKey.did, thisAgentKey.verKey, "signature", List(thisAgentKey.did))))

      }
    }

    "when called 'withAuthKeyAndEndpointDetail' for their auth key and endpoint" - {
      "should update did doc as expected" in {
        val didDoc = DidDocBuilder()
          .withDid(relDidPair.did)
          .withAuthKey(relDidPair.did, relDidPair.verKey)
          .withAuthKeyAndEndpointDetail(
            theirAgentKey.did, theirAgentKey.verKey, Set(AGENT_KEY_TAG),
            Left(LegacyRoutingDetail("agencyDID", theirAgentKey.did, theirAgentKey.verKey, "signature")))
          .didDoc

        didDoc.authorizedKeys_!.keys shouldBe Seq(
          AuthorizedKey(relDidPair.did, relDidPair.verKey),
          AuthorizedKey(theirAgentKey.did, theirAgentKey.verKey, Set(AGENT_KEY_TAG))
        )

        didDoc.endpoints_!.endpoints shouldBe List(EndpointADT(
            LegacyRoutingServiceEndpoint("agencyDID", theirAgentKey.did, theirAgentKey.verKey, "signature", List(theirAgentKey.did))))

      }
    }
  }
}
