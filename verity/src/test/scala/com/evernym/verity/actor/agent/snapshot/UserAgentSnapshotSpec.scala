package com.evernym.verity.actor.agent.snapshot

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.KeyCreated
import com.evernym.verity.actor.agent.{AgentWalletSetupProvider, SetupAgentEndpoint}
import com.evernym.verity.actor.agent.relationship.SelfRelationship
import com.evernym.verity.actor.agent.user.UserAgentState
import com.evernym.verity.actor.base.Done
import com.evernym.verity.testkit.mock.agent.MockEnvUtil._
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.actor.testkit.actor.OverrideConfig
import com.evernym.verity.constants.ActorNameConstants.USER_AGENT_REGION_ACTOR_NAME
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.testkit.mock.agent.MockEdgeAgent
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
        restartPersistentActor(ua)
        checkPersistentState(2, 2, 0)

        //check metrics for state size
        checkStateSizeMetrics("UserAgent", 66.0)
      }
    }
  }

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)

  lazy val mockEdgeAgent: MockEdgeAgent =
    buildMockEdgeAgent(mockAgencyAdmin, ecp.futureExecutionContext, ecp.walletFutureExecutionContext)

  lazy val userDID = mockEdgeAgent.myDIDDetail

  def initUserAgent(): Unit = {
    val agentPairwiseKey = prepareNewAgentWalletData(userDID.didPair, userAgentEntityId)
    ua ! SetupAgentEndpoint(userDID.didPair, agentPairwiseKey.didPair)
    expectMsg(Done)
    mockEdgeAgent.handleAgentCreatedRespForAgent(agentPairwiseKey.didPair)
  }

  def checkKeyCreatedEvent(keyCreated: KeyCreated, expectedForDID: DID): Unit = {
    keyCreated.forDID shouldBe expectedForDID
  }

  override def checkSnapshotState(state: UserAgentState,
                                  protoInstancesSize: Int): Unit = {
    state.publicIdentity.isDefined shouldBe false
    state.agencyDIDPair shouldBe mockAgencyAdmin.agencyPublicDid.map(_.didPair)
    state.agentWalletId shouldBe Option(userAgentEntityId)
    state.thisAgentKeyId.isDefined shouldBe true
    state.thisAgentKeyId.contains(userDID.did) shouldBe false

    state.relationshipReq.name shouldBe SelfRelationship.empty.name
    state.relationshipReq.myDidDoc.isDefined shouldBe true
    state.relationshipReq.myDidDoc_!.did shouldBe userDID.did

    //this is found only for pairwise actors and only for those protocols
    // which starts (the first message) from self-relationship actor and then
    // continues (rest messages) with a pairwise actor
    val expectedProtoInstancesSize = if (protoInstancesSize == 0) None else Option(protoInstancesSize)
    state.protoInstances.map(_.instances.size) shouldBe expectedProtoInstancesSize
  }

  override type StateType = UserAgentState
  override def regionActorName: String = USER_AGENT_REGION_ACTOR_NAME
  override def actorEntityId: String = userAgentEntityId

  override def executionContextProvider: ExecutionContextProvider = ecp
}
