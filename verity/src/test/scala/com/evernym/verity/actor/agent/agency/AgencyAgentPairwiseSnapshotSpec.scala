package com.evernym.verity.actor.agent.agency

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.agency.agent_provisioning.AgencyAgentPairwiseSpecBase
import com.evernym.verity.actor.agent.msghandler.incoming.PackedMsgParam
import com.evernym.verity.actor.persistence.stdPersistenceId
import com.evernym.verity.actor.persistence.transformer_registry.HasTransformationRegistry
import com.evernym.verity.actor.testkit.actor.OverrideConfig
import com.evernym.verity.actor.{AgencyPublicDid, KeyCreated, PersistentEventMsg, PersistentMsg, agentRegion}
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.constants.ActorNameConstants.AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.transformations.transformers.IdentityTransformer
import com.evernym.verity.transformations.transformers.v1.createPersistenceTransformerV1
import com.evernym.verity.util.Util
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.time.{Seconds, Span}


class AgencyAgentPairwiseSnapshotSpec
  extends AgencyAgentPairwiseSpecBase
    with HasTransformationRegistry
    with OverrideConfig {

  MetricsReader   //this makes sure it starts/add prometheus reporter and adds it to Kamon

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
        restartActor(aap)
        checkPersistentState(0, 2, 1)

        //check metrics
        checkStateSizeMetrics()
      }
    }
  }

  def checkPersistentState(expectedPersistedEvents: Int,
                           expectedPersistedSnapshots: Int,
                           protoInstancesSize: Int)
  : Unit = {
    eventually(timeout(Span(5, Seconds)), interval(Span(2, Seconds))) {
      val actualPersistedEvents = persTestKit.persistedInStorage(persId)
      actualPersistedEvents.size shouldBe expectedPersistedEvents
      val actualPersistedSnapshots = snapTestKit.persistedInStorage(persId).map(_._2)
      actualPersistedSnapshots.size shouldBe expectedPersistedSnapshots
      actualPersistedSnapshots.lastOption.map { snapshot =>
        val state = transformer.undo(snapshot.asInstanceOf[PersistentMsg]).asInstanceOf[AgencyAgentPairwiseState]
        checkSnapshotState(state, protoInstancesSize)
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
    aa ! PackedMsgParam(msg, reqMsgContext)
    val pm = expectMsgType[PackedMsg]
    val connectedResp = handleConnectedResp(pm)
    pairwiseDID = connectedResp.withPairwiseDID
    setPairwiseEntityId(pairwiseDID)
  }

  def fetchEvent[T](): T = {
    val rawEvent = persTestKit.expectNextPersistedType[PersistentEventMsg](persId)
    eventTransformation.undo(rawEvent).asInstanceOf[T]
  }

  def fetchSnapshot(): AgencyAgentState = {
    val rawEvent = snapTestKit.expectNextPersistedType[PersistentMsg](persId)
    snapshotTransformation.undo(rawEvent).asInstanceOf[AgencyAgentState]
  }

  def checkKeyCreatedEvent(keyCreated: KeyCreated, expectedForDID: DID): Unit = {
    keyCreated.forDID shouldBe expectedForDID
  }

  def checkSnapshotState(snap: AgencyAgentPairwiseState,
                         protoInstancesSize: Int): Unit = {
    snap.agencyDID shouldBe mockAgencyAdmin.agencyPublicDid.map(_.DID)
    snap.agentWalletSeed shouldBe Option(agencyAgentEntityId)
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

  def checkStateSizeMetrics(): Unit = {
    Thread.sleep(5000)  //to make sure metrics are recorded and available to read by this time
    val currentMetrics = MetricsReader.getNodeMetrics().metrics
    val stateSizeMetrics = currentMetrics.filter { m =>
      m.name.startsWith("as_akka_actor_agent_state") &&
      m.tags.getOrElse(Map.empty)("actor_class") == "AgencyAgentPairwise"
    }
    stateSizeMetrics.size shouldBe 12   //histogram metrics
    stateSizeMetrics.find(_.name == "as_akka_actor_agent_state_size_sum").foreach { v =>
      checkStateSizeSum(v.value, 380.0)
    }
  }

  def checkStateSizeSum(actualStateSizeSum: Double, expectedStateSizeSum: Double): Unit = {
    val diff = expectedStateSizeSum - actualStateSizeSum
    val average = (expectedStateSizeSum + actualStateSizeSum)/2
    val perDiff = (diff/average)*100
    perDiff <= 1 shouldBe true    //this is because of 1% error margin as explained here: https://github.com/kamon-io/Kamon/blob/master/core/kamon-core/src/main/scala/kamon/metric/Histogram.scala#L33
  }

  lazy val transformer = createPersistenceTransformerV1(encrKey, new IdentityTransformer)
  lazy val persId = stdPersistenceId(AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME, agencyAgentPairwiseEntityId)
  lazy val encrKey = {
    val secret = Util.saltedHashedName(agencyAgentPairwiseEntityId + "actor-wallet", appConfig)
    Util.getEventEncKey(secret, appConfig)
  }

  lazy val eventTransformation = legacyEventTransformer
  lazy val snapshotTransformation = persistenceTransformerV1
  override lazy val persistenceEncryptionKey: String = encrKey
}
