package com.evernym.verity.actor.agent.snapshot

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.agency.agent_provisioning.AgencyAgentPairwiseSpecBase
import com.evernym.verity.actor.agent.agency.{AgencyAgentPairwiseState, GetLocalAgencyIdentity}
import com.evernym.verity.actor.agent.msghandler.incoming.ProcessPackedMsg
import com.evernym.verity.actor.testkit.actor.OverrideConfig
import com.evernym.verity.actor.{AgencyPublicDid, KeyCreated, agentRegion}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.constants.ActorNameConstants.AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME
import com.evernym.verity.protocol.engine.DID
import com.typesafe.config.{Config, ConfigFactory}


class AgencyAgentPairwiseSnapshotSpec
  extends AgencyAgentPairwiseSpecBase
    with SnapshotSpecBase
    with OverrideConfig {

  override def overrideConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """verity.persistent-actor.base.AgencyAgentPairwise.snapshot {
        after-n-events = 1
        keep-n-snapshots = 2
        delete-events-on-snapshots = true
      }""")
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(PersistenceTestKitSnapshotPlugin.config)
  )

  import mockEdgeAgent.v_0_5_req._
  import mockEdgeAgent.v_0_5_resp._

  var pairwiseDID: DID = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    setupAgency()
  }

  "AgencyAgentPairwise actor" - {
    "as its state changes" - {
      "will write snapshot as per configuration" in {

        fetchAgencyKey()

        //send connection request (it will persist two new events: ProtocolIdDetailSet and AgentDetailSet)
        sendConnectMsg()
        checkPersistentState(0, 2, 1)

        //restart actor (so that snapshot gets applied)
        restartPersistentActor(aap)
        checkPersistentState(0, 2, 1)

        //check metrics
        checkStateSizeMetrics("AgencyAgentPairwise", 380.0)
      }
    }
  }

  lazy val aap = agentRegion(agencyAgentPairwiseEntityId, agencyAgentPairwiseRegion)

  def fetchAgencyKey(): Unit = {
    aa ! GetLocalAgencyIdentity()
    val dd = expectMsgType[AgencyPublicDid]
    mockEdgeAgent.handleFetchAgencyKey(dd)
  }

  def sendConnectMsg(): Unit = {
    val msg = prepareConnectMsg()
    aa ! ProcessPackedMsg(msg, reqMsgContext)
    val pm = expectMsgType[PackedMsg]
    val connectedResp = handleConnectedResp(pm)
    pairwiseDID = connectedResp.withPairwiseDID
    setPairwiseEntityId(pairwiseDID)
  }

  def checkKeyCreatedEvent(keyCreated: KeyCreated, expectedForDID: DID): Unit = {
    keyCreated.forDID shouldBe expectedForDID
  }

  override def checkSnapshotState(snap: AgencyAgentPairwiseState,
                         protoInstancesSize: Int): Unit = {
    snap.agencyDID shouldBe mockAgencyAdmin.agencyPublicDid.map(_.DID)
    snap.agentWalletId shouldBe Option(agencyAgentEntityId)
    snap.thisAgentKeyId should not be mockAgencyAdmin.agencyPublicDid.map(_.DID)
    snap.agencyDID should not be snap.thisAgentKeyId

    snap.relationshipReq.name shouldBe "pairwise"
    snap.relationshipReq.myDidDoc.isDefined shouldBe true
    snap.thisAgentKeyId.contains(snap.relationshipReq.myDidDoc_!.did) shouldBe true

    //this is found only for pairwise actors and only for those protocols
    // which starts (the first message) from self-relationship actor and then
    // continues (rest messages) with a pairwise actor
    val expectedProtoInstancesSize = if (protoInstancesSize == 0) None else Option(protoInstancesSize)
    snap.protoInstances.map(_.instances.size) shouldBe expectedProtoInstancesSize
  }

  override type StateType = AgencyAgentPairwiseState
  override def regionActorName: String = AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME
  override def actorEntityId: String = agencyAgentPairwiseEntityId
}
