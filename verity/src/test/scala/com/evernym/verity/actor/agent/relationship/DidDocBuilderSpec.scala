package com.evernym.verity.actor.agent.relationship

import java.util.UUID

import akka.actor.{ActorRef, Props}
import com.evernym.verity.actor.ShardUtil
import com.evernym.verity.actor.testkit.{HasBasicActorSystem, TestAppConfig}
import com.evernym.verity.testkit.{BasicAsyncSpec, CleansUpIndyClientFirst, HasTestAgentWalletAPI}
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, CLOUD_AGENT_KEY, EDGE_AGENT_KEY, RECIP_KEY}
import com.evernym.verity.actor.wallet.{CreateNewKey, CreateWallet, NewKeyCreated, WalletActor, WalletCreatedBase}
import com.evernym.verity.constants.ActorNameConstants.WALLET_REGION_ACTOR_NAME
import com.evernym.verity.vdrtools.ledger.IndyLedgerPoolConnManager
import com.evernym.verity.protocol.protocols.connecting.common.LegacyRoutingDetail
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.vault.WalletAPIParam
import org.scalatest.OptionValues

import scala.concurrent.ExecutionContext

class DidDocBuilderSpec
  extends BasicAsyncSpec
    with CleansUpIndyClientFirst
    with HasTestAgentWalletAPI
    with OptionValues
    with HasBasicActorSystem
    with ShardUtil {

  val walletActorRegion: ActorRef = createNonPersistentRegion(
      WALLET_REGION_ACTOR_NAME,
      Props(new WalletActor(appConfig, new IndyLedgerPoolConnManager(system, appConfig, executionContext), executionContext))
  )

  implicit val walletAPIParam: WalletAPIParam = WalletAPIParam(UUID.randomUUID().toString)

  lazy val (relDidPair, thisAgentKey, otherAgentKey, theirAgentKey) = {
    testWalletAPI.executeSync[WalletCreatedBase](CreateWallet())

    val relDIDPair = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey())
    val thisAgentKey = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = Option("0000000000000000000000000000TEST")))
    val otherAgentKey = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = Option("000000000000000000000000000OTHER")))
    val theirAgentKey = testWalletAPI.executeSync[NewKeyCreated](CreateNewKey(seed = Option("000000000000000000000000000THEIR")))          //key to represent their agent
    (relDIDPair, thisAgentKey, otherAgentKey, theirAgentKey)
  }

  lazy val ecp = TestExecutionContextProvider.ecp
  override implicit lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  implicit lazy val didDocBuilderParam: DidDocBuilderParam =
    DidDocBuilderParam(new TestAppConfig(), Option(thisAgentKey.did))

  "DidDocBuilder" - {

    "when building DidDoc with auth keys with and without ver keys" - {
      "should create expected DidDic" in {
        val didDoc =
          DidDocBuilder(executionContext)
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
          List(RoutingServiceEndpoint("http://localhost:9000/agency/msg", List.empty, List(thisAgentKey.did))))
      }
    }

    "when try to add duplicate auth key" - {
      "should throw appropriate exception" in {
        val ex1 = intercept[RuntimeException] {
          DidDocBuilder(executionContext)
            .withDid(relDidPair.did)
            .withAuthKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
            .withAuthKey(relDidPair.verKey, relDidPair.verKey, Set(CLOUD_AGENT_KEY))
        }
        ex1.getMessage shouldBe "duplicate auth keys not allowed"

        val ex2 = intercept[RuntimeException] {
          DidDocBuilder(executionContext)
            .withDid(relDidPair.did)
            .withAuthKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
            .withAuthKey(thisAgentKey.did, relDidPair.verKey, Set(CLOUD_AGENT_KEY))
        }
        ex2.getMessage shouldBe "duplicate auth keys not allowed"
      }
    }

    "when called 'updatedDidDocWithMigratedAuthKeys'" - {
      "should respond with updated did doc with proper auth ver keys" in {
        DidDocBuilder(executionContext)
          .withDid(relDidPair.did)
          .withAuthKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
          .withAuthKey(thisAgentKey.did, "", Set.empty)
          .updatedDidDocWithMigratedAuthKeys(Set.empty, standardWalletAPI)
          .map { didDoc =>
            didDoc.did shouldBe relDidPair.did
            didDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
              AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY)),
              AuthorizedKey(thisAgentKey.did, thisAgentKey.verKey, Set.empty),
            ))
            didDoc.endpoints.value shouldBe Endpoints.init(
              List(RoutingServiceEndpoint("http://localhost:9000/agency/msg", List.empty, List(thisAgentKey.did))))
          }
      }
    }

    "when called 'updatedDidDocWithMigratedAuthKeys' with key id as empty value" - {
      "should respond with updated did doc with proper auth ver keys" in {
        DidDocBuilder(executionContext)
          .withDid(relDidPair.did)
          .withAuthKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY))
          .withAuthKey("", "", Set.empty)
          .updatedDidDocWithMigratedAuthKeys(Set.empty, standardWalletAPI)
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
        val didDoc = DidDocBuilder(executionContext)
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
        val didDoc = DidDocBuilder(executionContext)
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

    "when called 'updatedDidDocWithMigratedAuthKeys' with possible dup auth keys" - {
      "should be updated successfully" in {
        val didDoc = DidDocBuilder(executionContext)
          .withDid(relDidPair.did)
          .withAuthKey(relDidPair.did, "", Set(EDGE_AGENT_KEY))
          .withAuthKeyAndEndpointDetail(
            theirAgentKey.did, theirAgentKey.verKey, Set(AGENT_KEY_TAG),
            Left(LegacyRoutingDetail("agencyDID", theirAgentKey.did, theirAgentKey.verKey, "signature")))
          .didDoc

        val updatedDidDoc =
          didDoc
            .updatedWithNewAuthKey(relDidPair.verKey, relDidPair.verKey, Set(RECIP_KEY))
            .updatedWithEndpoint(HttpEndpoint("1", "http://abc.xyz.com", Seq(relDidPair.did, relDidPair.verKey)))

        DidDocBuilder(executionContext, updatedDidDoc)
        .updatedDidDocWithMigratedAuthKeys(Set.empty, standardWalletAPI)
          .map { didDoc =>
            didDoc.did shouldBe relDidPair.did
            didDoc.authorizedKeys.value shouldBe AuthorizedKeys(Seq(
              AuthorizedKey(relDidPair.did, relDidPair.verKey, Set(EDGE_AGENT_KEY, RECIP_KEY)),
              AuthorizedKey(theirAgentKey.did, theirAgentKey.verKey, Set(AGENT_KEY_TAG))
            ))
            didDoc.endpoints_!.endpoints shouldBe List(
              EndpointADT(LegacyRoutingServiceEndpoint("agencyDID", theirAgentKey.did, theirAgentKey.verKey, "signature", List(theirAgentKey.did))),
              EndpointADT(HttpEndpoint("1", "http://abc.xyz.com", Seq(relDidPair.did)))
            )
          }
      }
    }
  }
}
