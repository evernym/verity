package com.evernym.verity.actor.persistence.recovery.legacy.verity1.v1

import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.agent.relationship.RelationshipTypeEnum.PAIRWISE_RELATIONSHIP
import com.evernym.verity.actor.agent.relationship.Tags.{AGENT_KEY_TAG, CLOUD_AGENT_KEY, EDGE_AGENT_KEY, OWNER_AGENT_KEY}
import com.evernym.verity.actor.agent.relationship._
import com.evernym.verity.actor.agent.user.UserAgentPairwiseState
import com.evernym.verity.actor.agent.{ConnectionStatus, Msg, MsgAndDelivery}
import com.evernym.verity.actor.persistence.recovery.base.BaseRecoveryActorSpec
import com.evernym.verity.actor.persistence.recovery.legacy.verity1.{AgencyAgentEventSetter, UserAgentEventSetter, UserAgentPairwiseEventSetter}
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
    closeClientWallets(Set(myAgencyAgentEntityId, mySelfRelAgentEntityId))
  }


  "Legacy User agent pairwise actor" - {

    "when started" - {
      "should respond as expected" in {
        val uapEventsBeforeStart = getEvents(myPairwiseRelAgentPersistenceId)
        uapRegion ! GetPersistentActorDetail
        expectMsgType[PersistentActorDetail]

        val walletServiceCountBeforeRestart = getStableWalletAPISucceedCountMetric
        val uapEventsBeforeRestart = getEvents(myPairwiseRelAgentPersistenceId)

        uapEventsBeforeRestart shouldBe uapEventsBeforeStart ++ getAuthKeyAddedEvents(List(myPairwiseRelDIDPair, myPairwiseRelAgentDIDPair, mySelfRelAgentDIDPair, theirPairwiseRelDIDPair))
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
    uas.mySelfRelDID shouldBe Option(mySelfRelDIDPair.DID)
    uas.ownerAgentDidPair shouldBe Some(mySelfRelAgentDIDPair)
    uas.connectionStatus shouldBe Some(ConnectionStatus(reqReceived = true, answerStatusCode = Status.MSG_STATUS_ACCEPTED.statusCode))
    uas.configs shouldBe Map.empty
    uas.thisAgentKeyId shouldBe Some(myPairwiseRelAgentDIDPair.DID)
    uas.agencyDIDPair shouldBe Some(myAgencyAgentDIDPair)
    uas.agentWalletId shouldBe Some(mySelfRelAgentEntityId)
    uas.msgAndDelivery shouldBe Some(
      MsgAndDelivery(
        msgs = Map(
          "001" -> Msg("connReq", myPairwiseRelDIDPair.DID, "MS-104", 1548446192302l, 1548446192302l,Some("002"), None, false),
          "002" -> Msg("connReqAnswer", theirPairwiseRelDIDPair.DID, "MS-104", 1548446192302l, 1548446192302l, None, None, false)
        ),
        Map.empty, Map.empty, Map.empty
      )
    )
    uas.relationship shouldBe Some(
      Relationship(
        PAIRWISE_RELATIONSHIP,
        "pairwise",
        Some(DidDoc(
          myPairwiseRelDIDPair.DID,
          Some(AuthorizedKeys(Seq(
            AuthorizedKey(myPairwiseRelDIDPair.DID, myPairwiseRelDIDPair.verKey, Set(EDGE_AGENT_KEY)),
            AuthorizedKey(myPairwiseRelAgentDIDPair.DID, myPairwiseRelAgentDIDPair.verKey, Set(CLOUD_AGENT_KEY)),
            AuthorizedKey(mySelfRelAgentDIDPair.DID, mySelfRelAgentDIDPair.verKey, Set(OWNER_AGENT_KEY))
          ))),
          Some(Endpoints(Vector.empty))
        )),
        Seq(
          DidDoc(
            theirPairwiseRelDIDPair.DID,
            Some(AuthorizedKeys(Seq(
              AuthorizedKey(theirPairwiseRelDIDPair.DID, theirPairwiseRelDIDPair.verKey, Set(EDGE_AGENT_KEY)),
              AuthorizedKey(theirPairwiseRelAgentDIDPair.DID, theirPairwiseRelAgentDIDPair.verKey, Set(AGENT_KEY_TAG)),
            ))),
            Some(Endpoints(Seq(
              EndpointADT(LegacyRoutingServiceEndpoint(
                theirAgencyAgentDIDPair.DID,
                theirPairwiseRelAgentDIDPair.DID,
                theirPairwiseRelAgentDIDPair.verKey,
                "dummy-signature",
                Seq(theirPairwiseRelAgentDIDPair.DID)
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
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}
