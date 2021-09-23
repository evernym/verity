package com.evernym.verity.actor.agent.snapshot


import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor.KeyCreated
import com.evernym.verity.actor.agent.agency.{AgencyAgentScaffolding, AgencyAgentState}
import com.evernym.verity.actor.agent.msghandler.incoming.ProcessPackedMsg
import com.evernym.verity.actor.agent.relationship.AnywiseRelationship
import com.evernym.verity.actor.testkit.actor.OverrideConfig
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.constants.ActorNameConstants.AGENCY_AGENT_REGION_ACTOR_NAME
import com.evernym.verity.did.DidStr
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.ExecutionContext


class AgencyAgentSnapshotSpec
  extends AgencyAgentScaffolding
    with AgentSnapshotSpecBase
    with OverrideConfig {

  override def specificConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """verity.persistent-actor.base.AgencyAgent.snapshot {
        after-n-events = 1
        keep-n-snapshots = 2
        delete-events-on-snapshots = false
      }""")
  )

  agencySetupSpecs()

  "AgencyAgent actor" - {
    "as its state changes" - {
      "will write snapshot as per configuration" in {

        // check current status (at this time, agency agent setup is done)
        checkPersistentState(2, 2, 0)
        val keyCreatedEvent = fetchEvent[KeyCreated]()
        checkKeyCreatedEvent(keyCreatedEvent, mockAgencyAdmin.agencyPublicDIDReq)

        //send connection request (it doesn't persist any new event)
        sendConnectMsg()
        checkPersistentState(2, 2, 0)

        //restart actor (so that snapshot gets applied)
        restartPersistentActor(aa)
        checkPersistentState(2, 2, 0)

        //check metrics for state size
        checkStateSizeMetrics("AgencyAgent", 212.0)
      }
    }
  }

  def sendConnectMsg(): Unit = {
    import mockEdgeAgent.v_0_5_req._
    val msg = prepareConnectMsg(useRandomDetails = true)
    aa ! ProcessPackedMsg(msg, reqMsgContext)
    expectMsgType[PackedMsg]
  }

  def checkKeyCreatedEvent(keyCreated: KeyCreated, expectedForDID: DidStr): Unit = {
    keyCreated.forDID shouldBe expectedForDID
  }

  override def checkSnapshotState(snap: AgencyAgentState,
                                  protoInstancesSize: Int): Unit = {
    snap.isEndpointSet shouldBe true
    snap.agencyDIDPair shouldBe mockAgencyAdmin.agencyPublicDid.map(_.didPair.toAgentDidPair)
    snap.agentWalletId shouldBe Option(agencyAgentEntityId)
    snap.thisAgentKeyId shouldBe mockAgencyAdmin.agencyPublicDid.map(_.DID)
    snap.agencyDIDPair.map(_.DID) shouldBe snap.thisAgentKeyId

    snap.relationshipReq.name shouldBe AnywiseRelationship.empty.name
    snap.relationshipReq.myDidDoc.isDefined shouldBe true
    snap.relationshipReq.myDidDoc_!.did shouldBe mockAgencyAdmin.agencyPublicDIDReq

    //this is found only for pairwise actors and only for those protocols
    // which starts (the first message) from self-relationship actor and then
    // continues (rest messages) with a pairwise actor
    val expectedProtoInstancesSize = if (protoInstancesSize == 0) None else Option(protoInstancesSize)
    snap.protoInstances.map(_.instances.size) shouldBe expectedProtoInstancesSize
  }

  override type StateType = AgencyAgentState
  override def regionActorName: String = AGENCY_AGENT_REGION_ACTOR_NAME
  override def actorEntityId: String = agencyAgentEntityId

  lazy val ecp: ExecutionContextProvider = new ExecutionContextProvider(appConfig)
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = ecp.futureExecutionContext

  override def executionContextProvider: ExecutionContextProvider = ecp

  /**
   * custom thread pool executor
   */
  override def futureWalletExecutionContext: ExecutionContext = ecp.walletFutureExecutionContext
}
