package com.evernym.verity.actor.persistence.recovery.latest.verity2.vas

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.ConnectionStatus
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, CLOUD_AGENT_KEY, EDGE_AGENT_KEY, OWNER_AGENT_KEY}
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.persistence.recovery.base.AgentIdentifiers._
import com.evernym.verity.actor.agent.user.UserAgentPairwiseState
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoveryActorSpec
import com.evernym.verity.actor.persistence.{GetPersistentActorDetail, PersistentActorDetail}
import com.evernym.verity.util2.Status
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext

class UserAgentPairwiseRecoverySpec
  extends BaseRecoveryActorSpec
    with AgencyAgentEventSetter
    with UserAgentEventSetter
    with UserAgentPairwiseEventSetter {

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupBasicAgencyAgent()
    setupBasicUserAgent()
    setupBasicUserAgentPairwise()
  }


  "User agent pairwise actor" - {

    "when started" - {
      "should respond as expected" in {
        val prevWalletServiceCount = getStableWalletAPISucceedCountMetric

        val uapEventsBeforeStart = getEvents(myPairwiseRelAgentPersistenceId)
        uapRegion ! GetPersistentActorDetail
        expectMsgType[PersistentActorDetail]

        val walletServiceCountBeforeRestart = getStableWalletAPISucceedCountMetric
        walletServiceCountBeforeRestart shouldBe prevWalletServiceCount
        val uapEventsBeforeRestart = getEvents(myPairwiseRelAgentPersistenceId)

        uapEventsBeforeRestart.containsSlice(uapEventsBeforeStart) shouldBe true
        restartActor(uapRegion)

        val walletServiceCountAfterRestart = getStableWalletAPISucceedCountMetric
        val uapEventsAfterRestart = getEvents(myPairwiseRelAgentPersistenceId)

        walletServiceCountAfterRestart shouldBe walletServiceCountBeforeRestart
        uapEventsAfterRestart shouldBe uapEventsBeforeRestart

        val userAgentPairwiseState = getSnapshot[UserAgentPairwiseState](myPairwiseRelAgentPersistenceId)
        assertUserAgentPairwiseState(userAgentPairwiseState)
      }
    }

    "when sent GetPersistentActorDetail at last" - {
      "should respond with correct detail" in {
        uapRegion ! GetPersistentActorDetail
        val ad = expectMsgType[PersistentActorDetail]
        //0 events recovered because it must have recovered from snapshot
        assertPersistentActorDetail(ad, myPairwiseRelAgentPersistenceId, 0)
      }
    }

  }

  def assertUserAgentPairwiseState(uas: UserAgentPairwiseState): Unit = {
    uas.mySelfRelDID shouldBe Option(mySelfRelDIDPair.did)
    uas.ownerAgentDidPair shouldBe Some(mySelfRelAgentDIDPair.toAgentDidPair)
    uas.connectionStatus shouldBe Some(ConnectionStatus(reqReceived = true, answerStatusCode = Status.MSG_STATUS_ACCEPTED.statusCode))
    uas.configs shouldBe Map.empty
    uas.thisAgentKeyId shouldBe Some(myPairwiseRelAgentDIDPair.did)
    uas.agencyDIDPair shouldBe Some(myAgencyAgentDIDPair.toAgentDidPair)
    uas.agentWalletId shouldBe Some(mySelfRelAgentEntityId)
    uas.msgAndDelivery.isDefined shouldBe true //TODO: test actual 'msgAndDelivery' object
    uas.relationship shouldBe Some(
      Relationship(
        PAIRWISE_RELATIONSHIP,
        "pairwise",
        Some(DidDoc(
          myPairwiseRelDIDPair.did,
          Some(AuthorizedKeys(Seq(
            AuthorizedKey(myPairwiseRelDIDPair.did, myPairwiseRelDIDPair.verKey, Set(EDGE_AGENT_KEY)),
            AuthorizedKey(myPairwiseRelAgentDIDPair.did, myPairwiseRelAgentDIDPair.verKey, Set(CLOUD_AGENT_KEY)),
            AuthorizedKey(mySelfRelAgentDIDPair.did, mySelfRelAgentDIDPair.verKey, Set(OWNER_AGENT_KEY))
          ))),
          Some(Endpoints(Vector.empty))
        )),
        Seq(
          DidDoc(
            theirPairwiseRelDIDPair.did,
            Some(AuthorizedKeys(Seq(
              AuthorizedKey(theirPairwiseRelDIDPair.did, theirPairwiseRelDIDPair.verKey, Set(AGENT_KEY_TAG)),
            ))),
            Some(Endpoints(Seq(
              EndpointADT(RoutingServiceEndpoint(
                "http://localhost:9001/agency/msg",
                Seq(theirPairwiseRelAgentDIDPair.did, theirAgencyAgentDIDPair.did),
                Seq(theirPairwiseRelDIDPair.did)
              ))
            )))
          )
        )
      )
    )
  }

  //NOTE: adding snapshotting to be able to get saved snapshot and assert the state
  override def overrideSpecificConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """verity.persistent-actor.base {
           UserAgentPairwise.snapshot {
             after-n-events = 1
             keep-n-snapshots = 2
             delete-events-on-snapshots = false
           }
         }
      """)
  )
  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  override def executionContextProvider: ExecutionContextProvider = ecp
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext
}
