package com.evernym.verity.actor.persistence.recovery.latest.verity2.vas

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.AgencyPublicDid
import com.evernym.verity.actor.agent.agency._
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.ANYWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.relationship.Tags.EDGE_AGENT_KEY
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.cluster_singleton.{ForKeyValueMapper, GetValue}
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoverySpec
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.constants.Constants.AGENCY_DID_KEY
import com.typesafe.config.{Config, ConfigFactory}

//this tests agency agent actor's recovery
class AgencyAgentRecoverySpec
  extends BaseRecoverySpec
    with AgencyAgentEventSetter{

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
    closeClientWallets(Set(myAgencyAgentEntityId))
  }

  "Agency agent actor" - {

    "when started" - {
      "should respond as expected" in {
        val prevWalletServiceCount = getWalletAPICallCount

        val aaEventsBeforeStart = getEvents(myAgencyAgentPersistenceId)
        aaRegion ! GetAgencyAgentDetail
        val adBeforeRestart = expectMsgType[AgencyAgentDetail]
        aaRegion ! GetLocalAgencyIdentity()
        val apdBeforeRestart = expectMsgType[AgencyPublicDid]
        aaRegion ! GetAgencyIdentity(myAgencyAgentDIDPair.DID)
        val aiBeforeRestart = expectMsgType[AgencyInfo]

        adBeforeRestart.didPair.validate()
        adBeforeRestart.did shouldBe myAgencyAgentDIDPair.DID
        adBeforeRestart.verKey shouldBe myAgencyAgentDIDPair.verKey
        adBeforeRestart.walletId shouldBe myAgencyAgentEntityId
        apdBeforeRestart.DID shouldBe myAgencyAgentDIDPair.DID
        apdBeforeRestart.verKey shouldBe myAgencyAgentDIDPair.verKey
        aiBeforeRestart.verKeyReq shouldBe myAgencyAgentDIDPair.verKey

        val walletServiceCountBeforeRestart = getWalletAPICallCount
        walletServiceCountBeforeRestart shouldBe prevWalletServiceCount

        val aaEventsBeforeRestart = getEvents(myAgencyAgentPersistenceId)
        aaEventsBeforeRestart shouldBe aaEventsBeforeStart

        restartActor(aaRegion)

        val walletServiceCountAfterRestart = getWalletAPICallCount
        val aaEventsAfterRestart = getEvents(myAgencyAgentPersistenceId)
        walletServiceCountAfterRestart shouldBe walletServiceCountBeforeRestart
        aaEventsAfterRestart shouldBe aaEventsBeforeRestart

        aaRegion ! GetAgencyAgentDetail
        val adPostRestart = expectMsgType[AgencyAgentDetail]
        aaRegion ! GetLocalAgencyIdentity()
        val apdPostRestart = expectMsgType[AgencyPublicDid]
        aaRegion ! GetAgencyIdentity(myAgencyAgentDIDPair.DID)
        val aiPostRestart = expectMsgType[AgencyInfo]

        adPostRestart.didPair.validate()
        adPostRestart.did shouldBe myAgencyAgentDIDPair.DID
        adPostRestart.verKey shouldBe myAgencyAgentDIDPair.verKey
        adPostRestart.walletId shouldBe myAgencyAgentEntityId
        apdPostRestart.DID shouldBe myAgencyAgentDIDPair.DID
        apdPostRestart.verKey shouldBe myAgencyAgentDIDPair.verKey
        aiPostRestart.verKeyReq shouldBe myAgencyAgentDIDPair.verKey

        adBeforeRestart shouldBe adPostRestart
        apdBeforeRestart shouldBe apdPostRestart
        aiBeforeRestart shouldBe aiPostRestart

        val agencyAgentState = getSnapshot[AgencyAgentState](myAgencyAgentPersistenceId)
        assertAgencyAgentState(agencyAgentState)
      }
    }

    "when sent GetPersistentActorDetail at last" - {
      "should respond with correct detail" in {
        aaRegion ! GetPersistentActorDetail
        val ad = expectMsgType[PersistentActorDetail]
        //0 events recovered because it must have recovered from snapshot
        assertPersistentActorDetail(ad, myAgencyAgentPersistenceId, 0)
      }
    }
  }

  "KeyValueMapper actor" - {
    "when sent GetActorDetail" - {
      "should respond with proper actor detail" in {
        platform.singletonParentProxy ! ForKeyValueMapper(GetPersistentActorDetail)
        val ad = expectMsgType[PersistentActorDetail]
        assertPersistentActorDetail(ad, keyValueMapperPersistenceId, 1)
      }
    }
    "when sent GetValue for AGENCY_DID" - {
      "should respond with proper agency DID" in {
        platform.singletonParentProxy ! ForKeyValueMapper(GetValue(AGENCY_DID_KEY))
        val agencyDID = expectMsgType[Option[String]]
        agencyDID.contains(myAgencyAgentDIDPair.DID) shouldBe true
      }
    }
  }

  def assertAgencyAgentState(aas: AgencyAgentState): Unit = {
    aas.isEndpointSet shouldBe true
    aas.thisAgentKeyId shouldBe Some(myAgencyAgentDIDPair.DID)
    aas.agencyDIDPair shouldBe Some(myAgencyAgentDIDPair)
    aas.agentWalletId shouldBe Some(myAgencyAgentEntityId)
    aas.relationship shouldBe Some(
      Relationship(
        ANYWISE_RELATIONSHIP,
        "anywise",
        Some(DidDoc(
          myAgencyAgentDIDPair.DID,
          Some(AuthorizedKeys(Seq(
            AuthorizedKey(myAgencyAgentDIDPair.DID, myAgencyAgentDIDPair.verKey, Set(EDGE_AGENT_KEY))
          ))),
          Some(Endpoints(Seq.empty))
        )),
        Seq.empty
      )
    )
  }

  //NOTE: adding snapshotting to be able to get saved snapshot and assert the state
  override def overrideConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """verity.persistent-actor.base {
           AgencyAgent.snapshot {
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