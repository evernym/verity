package com.evernym.verity.actor.agent.snapshot

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.KeyCreated
import com.evernym.verity.actor.agent.SetupAgentEndpoint
import com.evernym.verity.actor.agent.relationship.SelfRelationship
import com.evernym.verity.actor.agent.user.UserAgentState
import com.evernym.verity.actor.base.Done
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.testkit.actor.OverrideConfig
import com.evernym.verity.constants.ActorNameConstants.USER_AGENT_REGION_ACTOR_NAME
import com.evernym.verity.protocol.engine.{DID, VerKey}
import com.evernym.verity.protocol.protocols.agentprovisioning.common.AgentWalletSetupProvider
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.mock.edge_agent.MockEdgeAgent
import com.typesafe.config.{Config, ConfigFactory}

class UserAgentSnapshotSpec
  extends BasicSpec
    with PersistentActorSpec
    with SnapshotSpecBase
    with AgentWalletSetupProvider
    with OverrideConfig {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
  }

  override def overrideConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """verity.persistent-actor.base.UserAgent.snapshot {
        after-n-events = 1
        keep-n-snapshots = 2
        delete-events-on-snapshots = false
      }""")
      .withFallback(EventSourcedBehaviorTestKit.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
  )

  "UserAgent actor" - {
    "as its state changes" - {
      "will write snapshot as per configuration" in {

        // check current status (at this time, agency agent setup is done)
        checkPersistentState(0, 0, 0)

        //init agent request
        initUserAgent()
        checkPersistentState(2, 2, 0)

        //restart actor
        restartActor(ua)
        checkPersistentState(2, 2, 0)

        //check metrics for state size
        checkStateSizeMetrics("UserAgent", 66.0)
      }
    }
  }

  lazy val mockEdgeAgent: MockEdgeAgent = buildMockConsumerEdgeAgent(
    platform.agentActorContext.appConfig, mockAgencyAdmin)

  lazy val userDID: DID = mockEdgeAgent.myDIDDetail.did
  lazy val userDIDVerKey: VerKey = mockEdgeAgent.getVerKeyFromWallet(userDID)

  def initUserAgent(): Unit = {
    val agentPairwiseKey = prepareNewAgentWalletData(userDID, userDIDVerKey, userAgentEntityId)
    ua ! SetupAgentEndpoint(userDID, agentPairwiseKey.did)
    expectMsg(Done)
    mockEdgeAgent.handleAgentCreatedRespForAgent(agentPairwiseKey.did, agentPairwiseKey.verKey)
  }

  def checkKeyCreatedEvent(keyCreated: KeyCreated, expectedForDID: DID): Unit = {
    keyCreated.forDID shouldBe expectedForDID
  }

  override def checkSnapshotState(state: UserAgentState,
                                  protoInstancesSize: Int): Unit = {
    state.publicIdentity.isDefined shouldBe false
    state.agencyDID shouldBe mockAgencyAdmin.agencyPublicDid.map(_.DID)
    state.agentWalletId shouldBe Option(userAgentEntityId)
    state.thisAgentKeyId.isDefined shouldBe true
    state.thisAgentKeyId.contains(userDID) shouldBe false

    state.relationshipReq.name shouldBe SelfRelationship.empty.name
    state.relationshipReq.myDidDoc.isDefined shouldBe true
    state.relationshipReq.myDidDoc_!.did shouldBe userDID

    //this is found only for pairwise actors and only for those protocols
    // which starts (the first message) from self-relationship actor and then
    // continues (rest messages) with a pairwise actor
    val expectedProtoInstancesSize = if (protoInstancesSize == 0) None else Option(protoInstancesSize)
    state.protoInstances.map(_.instances.size) shouldBe expectedProtoInstancesSize
  }

  override type StateType = UserAgentState
  override def regionActorName: String = USER_AGENT_REGION_ACTOR_NAME
  override def actorEntityId: String = userAgentEntityId
}
