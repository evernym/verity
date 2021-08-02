package com.evernym.verity.actor.persistence.recovery.latest.verity2.cas

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.AgentDetail
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.SELF_RELATIONSHIP
import com.evernym.verity.actor.agent.relationship.Tags.{CLOUD_AGENT_KEY, EDGE_AGENT_KEY}
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.agent.user.UserAgentState
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoveryActorSpec
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext


class UserAgentRecoverySpec
  extends BaseRecoveryActorSpec
    with AgencyAgentEventSetter
    with UserAgentEventSetter {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
    setupBasicUserAgent()
    closeClientWallets(Set(myAgencyAgentEntityId, mySelfRelAgentEntityId))
  }

  "User agent actor" - {

    "when started" - {
      "should respond as expected" in {
        val prevWalletServiceCount = getStableWalletAPISucceedCountMetric

        val uaEventsBeforeStart = getEvents(mySelfRelAgentPersistenceId)
        uaRegion ! GetPersistentActorDetail
        expectMsgType[PersistentActorDetail]

        val walletServiceCountBeforeRestart = getStableWalletAPISucceedCountMetric
        walletServiceCountBeforeRestart shouldBe prevWalletServiceCount

        val uaEventsBeforeRestart = getEvents(mySelfRelAgentPersistenceId)
        uaEventsBeforeRestart shouldBe uaEventsBeforeStart

        restartActor(uaRegion)

        val walletServiceCountAfterRestart = getStableWalletAPISucceedCountMetric
        val uaEventsAfterRestart = getEvents(mySelfRelAgentPersistenceId)

        walletServiceCountAfterRestart shouldBe walletServiceCountBeforeRestart
        uaEventsAfterRestart shouldBe uaEventsBeforeRestart

        val userAgentState = getSnapshot[UserAgentState](mySelfRelAgentPersistenceId)
        assertUserAgentState(userAgentState)
      }
    }

    "when sent GetPersistentActorDetail at last" - {
      "should respond with correct detail" in {
        uaRegion ! GetPersistentActorDetail
        val ad = expectMsgType[PersistentActorDetail]
        //0 events recovered because it must have recovered from snapshot
        assertPersistentActorDetail(ad, mySelfRelAgentPersistenceId, 0)
      }
    }
  }

  def assertUserAgentState(uas: UserAgentState): Unit = {
    uas.publicIdentity shouldBe None
    uas.sponsorRel shouldBe None
    uas.configs shouldBe Map.empty
    uas.msgAndDelivery shouldBe None
    uas.thisAgentKeyId shouldBe Option(mySelfRelAgentDIDPair.DID)
    uas.agencyDIDPair shouldBe Option(myAgencyAgentDIDPair)
    uas.agentWalletId shouldBe Some(mySelfRelAgentEntityId)
    uas.relationship shouldBe Some(
      Relationship(
        SELF_RELATIONSHIP,
        "self",
        Some(DidDoc(
          mySelfRelDIDPair.DID,
          Some(AuthorizedKeys(Seq(
            AuthorizedKey(mySelfRelDIDPair.DID, mySelfRelDIDPair.verKey, Set(EDGE_AGENT_KEY)),
            AuthorizedKey(mySelfRelAgentDIDPair.DID, mySelfRelAgentDIDPair.verKey, Set(CLOUD_AGENT_KEY))
          ))),
          Some(Endpoints(Seq(
            EndpointADT(PushEndpoint("push-token", "firebase-push-token"))
          )))
        )),
        Seq.empty
      )
    )
    //pairwise connection related info
    uas.relationshipAgents shouldBe Map(myPairwiseRelDIDPair.DID -> AgentDetail(myPairwiseRelDIDPair.DID, myPairwiseRelAgentDIDPair.DID))
  }

  //NOTE: adding snapshotting to be able to get saved snapshot and assert the state
  override def overrideSpecificConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """verity.persistent-actor.base {
           UserAgent.snapshot {
             after-n-events = 1
             keep-n-snapshots = 2
             delete-events-on-snapshots = false
           }
         }
      """)
  )
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}