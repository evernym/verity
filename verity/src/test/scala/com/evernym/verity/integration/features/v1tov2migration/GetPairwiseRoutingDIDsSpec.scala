package com.evernym.verity.integration.features.v1tov2migration

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.user.GetPairwiseRoutingDIDsResp
import com.evernym.verity.actor.{AgentDetailSet, AgentKeyCreated, OwnerDIDSet}
import com.evernym.verity.actor.persistence.recovery.base.AgentIdentifiers._
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.ActorNameConstants.ACTOR_TYPE_USER_AGENT_ACTOR
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.{EAS, VerityProviderBaseSpec}
import com.evernym.verity.testkit.util.HttpUtil
import com.typesafe.config.ConfigFactory


class GetPairwiseRoutingDIDsSpec
  extends VerityProviderBaseSpec
    with SdkProvider {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupUserAgent()
  }


  lazy val issuerEAS = VerityEnvBuilder().withConfig(TEST_KIT_CONFIG).build(EAS)
  lazy val issuerRestSDK = setupIssuerRestSdk(issuerEAS, futureExecutionContext)

  "Verity1ToVerity2Migration Internal API" - {
    "when asked for pairwise routing DIDs" - {

      "with no batching" - {
        "should respond with all pairwise DIDs" in {
          val resp = getPairwiseDIDs("")
          val expectedPairwiseDIDs = (1 to 10).map(i => s"agentDID$i")
          resp.totalRemainingItems shouldBe 0
          resp.pairwiseRoutingDIDs.diff(expectedPairwiseDIDs) shouldBe Seq.empty
        }
      }

      "with fixed batch size" - {
        "should return as per the request" in {
          val batch1 = getPairwiseDIDs("?totalItemsReceived=0&batchSize=3")
          batch1.pairwiseRoutingDIDs.size shouldBe 3
          batch1.totalRemainingItems shouldBe 7
          val batch2 = getPairwiseDIDs(s"?totalItemsReceived=${batch1.pairwiseRoutingDIDs.size}&batchSize=3")
          batch2.pairwiseRoutingDIDs.size shouldBe 3
          batch2.totalRemainingItems shouldBe 4
          val batch3 = getPairwiseDIDs(s"?totalItemsReceived=${(batch1.pairwiseRoutingDIDs++batch2.pairwiseRoutingDIDs).size}&batchSize=3")
          batch3.pairwiseRoutingDIDs.size shouldBe 3
          batch3.totalRemainingItems shouldBe 1
          val batch4 = getPairwiseDIDs(s"?totalItemsReceived=${(batch1.pairwiseRoutingDIDs++batch2.pairwiseRoutingDIDs++batch3.pairwiseRoutingDIDs).size}&batchSize=3")
          batch4.pairwiseRoutingDIDs.size shouldBe 1
          batch4.totalRemainingItems shouldBe 0

          val actualRoutingDIDs =
            batch1.pairwiseRoutingDIDs ++
              batch2.pairwiseRoutingDIDs ++
              batch3.pairwiseRoutingDIDs ++
              batch4.pairwiseRoutingDIDs
          val expectedPairwiseDIDs = (1 to 10).map(i => s"agentDID$i")

          actualRoutingDIDs.diff(expectedPairwiseDIDs) shouldBe Seq.empty
        }
      }

      "with changing batch size" - {
        "should return as per the request" in {
          val batch1 = getPairwiseDIDs("?totalItemsReceived=0&batchSize=3")
          batch1.pairwiseRoutingDIDs.size shouldBe 3
          batch1.totalRemainingItems shouldBe 7
          val batch2 = getPairwiseDIDs(s"?totalItemsReceived=${batch1.pairwiseRoutingDIDs.size}&batchSize=4")
          batch2.pairwiseRoutingDIDs.size shouldBe 4
          batch2.totalRemainingItems shouldBe 3
          val batch3 = getPairwiseDIDs(s"?totalItemsReceived=${(batch1.pairwiseRoutingDIDs++batch2.pairwiseRoutingDIDs).size}&batchSize=5")
          batch3.pairwiseRoutingDIDs.size shouldBe 3
          batch3.totalRemainingItems shouldBe 0

          val actualRoutingDIDs =
            batch1.pairwiseRoutingDIDs ++
              batch2.pairwiseRoutingDIDs ++
              batch3.pairwiseRoutingDIDs
          val expectedPairwiseDIDs = (1 to 10).map(i => s"agentDID$i")

          actualRoutingDIDs.diff(expectedPairwiseDIDs) shouldBe Seq.empty
        }
      }

      "with same input" - {
        "should return same result" in {
          val resp1 = getPairwiseDIDs("?totalItemsReceived=3&batchSize=3")
          val resp2 = getPairwiseDIDs("?totalItemsReceived=3&batchSize=3")
          resp1 shouldBe resp2
        }
      }
    }
  }

  private def getPairwiseDIDs(queryParam: String): GetPairwiseRoutingDIDsResp = {
    val urlSuffix = s"agency/internal/maintenance/v1tov2migration/agent/${mySelfRelAgentDIDPair.did}/pairwiseRoutingDIDs"
    val apiResp = HttpUtil.sendGET(issuerRestSDK.buildFullUrl(s"$urlSuffix$queryParam"))(futureExecutionContext)
    val respString = HttpUtil.parseHttpResponseAsString(apiResp)(futureExecutionContext)
    DefaultMsgCodec.fromJson[GetPairwiseRoutingDIDsResp](respString)
  }

  def setupUserAgent(): Unit = {
    val basicUserAgentEvents = scala.collection.immutable.Seq(
      OwnerDIDSet(mySelfRelDIDPair.did, mySelfRelDIDPair.verKey),
      AgentKeyCreated(mySelfRelAgentDIDPair.did, mySelfRelAgentDIDPair.verKey)
    )
    val pairwiseDIDEvents = (1 to 10).map { i =>
      AgentDetailSet(s"pairwiseDID$i", s"agentDID$i")
    }
    issuerEAS.persStoreTestKit.storeAgentRoute(mySelfRelAgentDIDPair.did, ACTOR_TYPE_USER_AGENT_ACTOR, mySelfRelAgentPersistenceId.entityId)
    issuerEAS.persStoreTestKit.addEventsToPersistentStorage(mySelfRelAgentPersistenceId, basicUserAgentEvents ++ pairwiseDIDEvents)
  }

  private val TEST_KIT_CONFIG =
    ConfigFactory.empty
      .withFallback(EventSourcedBehaviorTestKit.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)

}
