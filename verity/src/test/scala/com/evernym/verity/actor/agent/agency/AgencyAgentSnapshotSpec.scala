package com.evernym.verity.actor.agent.agency

import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import com.evernym.verity.actor.agent.msghandler.incoming.PackedMsgParam
import com.evernym.verity.actor.agent.relationship.AnywiseRelationship
import com.evernym.verity.actor.persistence.transformer_registry.HasTransformationRegistry
import com.evernym.verity.actor.persistence.stdPersistenceId
import com.evernym.verity.actor.testkit.actor.OverrideConfig
import com.evernym.verity.actor.{KeyCreated, PersistentData, TransformedEvent}
import com.evernym.verity.agentmsg.msgpacker.PackedMsg
import com.evernym.verity.constants.ActorNameConstants.AGENCY_AGENT_REGION_ACTOR_NAME
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.transformations.transformers.IdentityTransformer
import com.evernym.verity.transformations.transformers.v1.createPersistenceTransformerV1
import com.evernym.verity.util.Util
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.time.{Seconds, Span}


class AgencyAgentSnapshotSpec
  extends AgencyAgentScaffolding
    with HasTransformationRegistry
    with OverrideConfig {

  MetricsReader   //this makes sure it starts/add prometheus reporter and adds it to Kamon

  override def overrideConfig: Option[Config] = Option(
    ConfigFactory.parseString(
      """verity.persistent-actor.base.AgencyAgent.snapshot {
        after-n-events = 5
        keep-n-snapshots = 2
        delete-events-on-snapshots = false
      }""")
    .withFallback(EventSourcedBehaviorTestKit.config)
    .withFallback(PersistenceTestKitSnapshotPlugin.config)
  )

  agencySetupSpecs()

  "AgencyAgent actor" - {
    "as its state changes" - {
      "will write snapshot as per configuration" in {

        // check current status (at this time, agency agent setup is done)
        checkPersistentState(5, 1, 1, 0)
        val keyCreatedEvent = fetchEvent[KeyCreated]()
        checkKeyCreatedEvent(keyCreatedEvent, mockAgencyAdmin.agencyPublicDIDReq)

        //NOTE: each connect message (sendConnectMsg method call below) will
        // end up in persisting 3 events in AgencyAgent actor (mostly related to thread context, proto msg sent/received orders)

        //send first connection request
        sendConnectMsg()
        checkPersistentState(8, 1, 1, 0)

        //send second connection request
        sendConnectMsg()
        checkPersistentState(11, 2, 3, 0)

        //send third connection request
        sendConnectMsg()
        checkPersistentState(14, 2, 3, 0)

        //send fourth connection request (snapshot size will still be 2 because of 'keep-n-snapshots' = 2)
        sendConnectMsg()
        checkPersistentState(17, 2, 5, 0)

        //restart actor (so that snapshot gets applied)
        restartActor()
        checkStateSizeMetrics()
      }
    }
  }

  def checkPersistentState(expectedPersistedEvents: Int,
                           expectedPersistedSnapshots: Int,
                           threadContextSize: Int,
                           protoInstancesSize: Int)
  : Unit = {
    eventually(timeout(Span(5, Seconds)), interval(Span(2, Seconds))) {
      val actualPersistedEvents = persTestKit.persistedInStorage(persId)
      actualPersistedEvents.size shouldBe expectedPersistedEvents
      val actualPersistedSnapshots = snapTestKit.persistedInStorage(persId).map(_._2)
      actualPersistedSnapshots.size shouldBe expectedPersistedSnapshots
      actualPersistedSnapshots.lastOption.map { snapshot =>
        val state = transformer.undo(snapshot.asInstanceOf[PersistentData]).asInstanceOf[AgencyAgentState]
        checkSnapshotState(state, threadContextSize, protoInstancesSize)
      }
    }
  }

  def sendConnectMsg(): Unit = {
    import mockEdgeAgent.v_0_5_req._
    val msg = prepareConnectMsg(useRandomDetails = true)
    aa ! PackedMsgParam(msg, reqMsgContext)
    expectMsgType[PackedMsg]
  }

  def fetchEvent[T](): T = {
    val rawEvent = persTestKit.expectNextPersistedType[TransformedEvent](persId)
    eventTransformation.undo(rawEvent).asInstanceOf[T]
  }

  def fetchSnapshot(): AgencyAgentState = {
    val rawEvent = snapTestKit.expectNextPersistedType[PersistentData](persId)
    snapshotTransformation.undo(rawEvent).asInstanceOf[AgencyAgentState]
  }

  def checkKeyCreatedEvent(keyCreated: KeyCreated, expectedForDID: DID): Unit = {
    keyCreated.forDID shouldBe expectedForDID
  }

  def checkSnapshotState(snap: AgencyAgentState,
                         threadContextSize: Int,
                         protoInstancesSize: Int): Unit = {
    snap.isEndpointSet shouldBe true
    snap.agencyDID shouldBe mockAgencyAdmin.agencyPublicDid.map(_.DID)
    snap.agentWalletSeed shouldBe Option(agencyAgentEntityId)
    snap.thisAgentKeyId shouldBe mockAgencyAdmin.agencyPublicDid.map(_.DID)
    snap.agencyDID shouldBe snap.thisAgentKeyId

    snap.relationshipReq.name shouldBe AnywiseRelationship.empty.name
    snap.relationshipReq.myDidDoc.isDefined shouldBe true
    snap.relationshipReq.myDidDoc_!.did shouldBe mockAgencyAdmin.agencyPublicDIDReq

    val expectedThreadContextSize = if (threadContextSize == 0) None else Option(threadContextSize)
    snap.threadContext.map(_.contexts.size) shouldBe expectedThreadContextSize

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
      m.tags.getOrElse(Map.empty)("actor_class") == "AgencyAgent"
    }
    stateSizeMetrics.size shouldBe 12   //histogram metrics
    stateSizeMetrics.find(_.name == "as_akka_actor_agent_state_size_sum").foreach { v =>
      checkStateSizeSum(v.value, 623.0)
    }
  }

  def checkStateSizeSum(actualStateSizeSum: Double, expectedStateSizeSum: Double): Unit = {
    val diff = expectedStateSizeSum - actualStateSizeSum
    val average = (expectedStateSizeSum + actualStateSizeSum)/2
    val perDiff = (diff/average)*100
    perDiff <= 1 shouldBe true    //this is because of 1% error margin as explained here: https://github.com/kamon-io/Kamon/blob/master/core/kamon-core/src/main/scala/kamon/metric/Histogram.scala#L33
  }

  lazy val transformer = createPersistenceTransformerV1(encrKey, new IdentityTransformer)
  lazy val persId = stdPersistenceId(AGENCY_AGENT_REGION_ACTOR_NAME, agencyAgentEntityId)
  lazy val encrKey = {
    val secret = Util.saltedHashedName(agencyAgentEntityId + "actor-wallet", appConfig)
    Util.getEventEncKey(secret, appConfig)
  }

  val eventTransformation = legacyEventTransformer
  val snapshotTransformation = persistenceTransformerV1
  override lazy val persistenceEncryptionKey: String = encrKey
}
