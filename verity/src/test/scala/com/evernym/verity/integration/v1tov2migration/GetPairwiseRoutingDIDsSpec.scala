package com.evernym.verity.integration.v1tov2migration

import akka.actor.ActorSystem
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.user.GetPairwiseRoutingDIDsResp
import com.evernym.verity.actor.{AgentDetailSet, AgentKeyCreated, OwnerDIDSet}
import com.evernym.verity.actor.persistence.recovery.base.{BasePersistentStore, PersistenceIdParam}
import com.evernym.verity.actor.testkit.CommonSpecUtil
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.constants.ActorNameConstants.{ACTOR_TYPE_USER_AGENT_ACTOR, USER_AGENT_REGION_ACTOR_NAME}
import com.evernym.verity.integration.base.sdk_provider.SdkProvider
import com.evernym.verity.integration.base.verity_provider.node.local.VerityLocalNode
import com.evernym.verity.integration.base.{EAS, VerityProviderBaseSpec}
import com.evernym.verity.util.TestExecutionContextProvider
import com.evernym.verity.util2.ExecutionContextProvider
import com.typesafe.config.ConfigFactory

import java.util.UUID
import scala.concurrent.ExecutionContext


class GetPairwiseRoutingDIDsSpec
  extends VerityProviderBaseSpec
    with SdkProvider
    with BasePersistentStore {

  val userAgentDID = CommonSpecUtil.generateNewDid().did
  val userAgentPersistenceIdParam = PersistenceIdParam(USER_AGENT_REGION_ACTOR_NAME, UUID.randomUUID().toString)

  override def beforeAll(): Unit = {
    //set up data
    super.beforeAll()
    val mySelfRelDIDPair = CommonSpecUtil.generateNewDid()
    val mySelfRelAgentDIDPair = CommonSpecUtil.generateNewDid()
    val basicUserAgentEvents = scala.collection.immutable.Seq(
      OwnerDIDSet(mySelfRelDIDPair.did, mySelfRelDIDPair.verKey),
      AgentKeyCreated(mySelfRelAgentDIDPair.did, mySelfRelAgentDIDPair.verKey)
    )
    val pairwiseDIDEvents = (1 to 10).map { i =>
      AgentDetailSet(s"pairwiseDID$i", s"agentDID$i")
    }
    storeAgentRoute(userAgentDID, ACTOR_TYPE_USER_AGENT_ACTOR, userAgentPersistenceIdParam.entityId)
    addEventsToPersistentStorage(userAgentPersistenceIdParam, basicUserAgentEvents ++ pairwiseDIDEvents)
  }

  lazy val issuerEAS = VerityEnvBuilder.default().withConfig(TEST_KIT_CONFIG).build(EAS)
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
    val basePath = s"agency/internal/maintenance/v1tov2migration/agent/$userAgentDID/pairwiseRoutingDIDs"
    val apiResp = issuerRestSDK.sendGET(s"$basePath$queryParam")
    val respString = issuerRestSDK.parseHttpResponseAsString(apiResp)
    DefaultMsgCodec.fromJson[GetPairwiseRoutingDIDsResp](respString)
  }

  lazy val ecp: ExecutionContextProvider = TestExecutionContextProvider.ecp
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
  override def executionContextProvider: ExecutionContextProvider = ecp

  override implicit val system: ActorSystem = issuerEAS.nodes.head.asInstanceOf[VerityLocalNode].platform.actorSystem

  lazy val TEST_KIT_CONFIG =
    ConfigFactory.empty
      .withFallback(EventSourcedBehaviorTestKit.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)

}
