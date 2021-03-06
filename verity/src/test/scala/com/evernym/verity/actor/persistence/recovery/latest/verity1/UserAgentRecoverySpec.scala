package com.evernym.verity.actor.persistence.recovery.latest.verity1

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.SELF_RELATIONSHIP
import com.evernym.verity.actor.agent.relationship.Tags.{CLOUD_AGENT_KEY, EDGE_AGENT_KEY, RECIP_KEY}
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.agent.user.UserAgentState
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoverySpec
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.typesafe.config.{Config, ConfigFactory}

class UserAgentRecoverySpec
  extends BaseRecoverySpec
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
        val prevWalletServiceCount = getWalletAPICallCount

        val uaEventsBeforeStart = getEvents(mySelfRelAgentPersistenceId)
        uaRegion ! GetPersistentActorDetail
        expectMsgType[PersistentActorDetail]

        val walletServiceCountBeforeRestart = getWalletAPICallCount
        walletServiceCountBeforeRestart shouldBe prevWalletServiceCount

        val uaEventsBeforeRestart = getEvents(mySelfRelAgentPersistenceId)
        uaEventsBeforeRestart shouldBe uaEventsBeforeStart

        restartActor(uaRegion)

        val walletServiceCountAfterRestart = getWalletAPICallCount
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
    uas.relationshipAgents shouldBe Map.empty
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
            AuthorizedKey(mySelfRelAgentDIDPair.DID, mySelfRelAgentDIDPair.verKey, Set(CLOUD_AGENT_KEY)),
            AuthorizedKey(mySelfRelDIDPair.DID, mySelfRelDIDPair.verKey, Set(EDGE_AGENT_KEY, RECIP_KEY))
          ))),
          Some(Endpoints(Seq(
            //TODO: shouldn't the auth key be the "cloud agent key id" instead of the "edge key id"?
            EndpointADT(HttpEndpoint("1", "http://abc.xyz.com", Seq(mySelfRelDIDPair.DID)))
          )))
        )),
        Seq.empty
      )
    )
  }

  //NOTE: adding snapshotting to be able to get saved snapshot and assert the state
  override def overrideConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """verity.persistent-actor.base {
           UserAgent.snapshot {
             after-n-events = 1
             keep-n-snapshots = 2
             delete-events-on-snapshots = false
           }
         }
      """)
      .withFallback(EventSourcedBehaviorTestKit.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
  )
}