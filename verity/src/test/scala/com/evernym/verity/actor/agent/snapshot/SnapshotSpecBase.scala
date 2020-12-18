package com.evernym.verity.actor.agent.snapshot

import com.evernym.verity.actor.PersistentMsg
import com.evernym.verity.actor.agent.agency.AgencyAgentState
import com.evernym.verity.actor.persistence.stdPersistenceId
import com.evernym.verity.actor.persistence.transformer_registry.HasTransformationRegistry
import com.evernym.verity.actor.testkit.{AgentSpecHelper, PersistentActorSpec}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.metrics.MetricsReader
import com.evernym.verity.testkit.BasicSpec
import com.evernym.verity.transformations.transformers.v1.createPersistenceTransformerV1
import com.evernym.verity.util.Util
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Seconds, Span}

trait SnapshotSpecBase
  extends AgentSpecHelper
    with HasTransformationRegistry
    with Eventually { this: PersistentActorSpec with BasicSpec =>

  MetricsReader   //this makes sure it starts/add prometheus reporter and adds it to Kamon

  def checkStateSizeMetrics(actorClass: String, expectSize: Double): Unit = {
    java.lang.Thread.sleep(5000)    //to make sure metrics are recorded and available to read by this time
    val currentMetrics = MetricsReader.getNodeMetrics().metrics
    val stateSizeMetrics = currentMetrics.filter { m =>
      m.name.startsWith("as_akka_actor_agent_state") &&
        m.tags.getOrElse(Map.empty)("actor_class") == actorClass
    }
    stateSizeMetrics.size shouldBe 12   //histogram metrics
    stateSizeMetrics.find(_.name == "as_akka_actor_agent_state_size_sum").foreach { v =>
      checkStateSizeSum(v.value, expectSize)
    }
  }

  def checkStateSizeSum(actualStateSizeSum: Double, expectedStateSizeSum: Double): Unit = {
    val diff = expectedStateSizeSum - actualStateSizeSum
    val average = (expectedStateSizeSum + actualStateSizeSum)/2
    val perDiff = (diff/average)*100
    perDiff <= 1 shouldBe true    //this is because of 1% error margin as explained here: https://github.com/kamon-io/Kamon/blob/master/core/kamon-core/src/main/scala/kamon/metric/Histogram.scala#L33
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
        val state = transformer.undo(snapshot.asInstanceOf[PersistentMsg]).asInstanceOf[StateType]
        checkSnapshotState(state, protoInstancesSize)
      }
    }
  }

  def fetchEvent[T](): T = {
    val rawEvent = persTestKit.expectNextPersistedType[PersistentMsg](persId)
    eventTransformation.undo(rawEvent).asInstanceOf[T]
  }

  def fetchSnapshot(): AgencyAgentState = {
    val rawEvent = snapTestKit.expectNextPersistedType[PersistentMsg](persId)
    snapshotTransformation.undo(rawEvent).asInstanceOf[AgencyAgentState]
  }

  type StateType

  def checkSnapshotState(state: StateType, protoInstancesSize: Int)
  def appConfig: AppConfig
  def regionActorName: String
  def actorEntityId: String

  lazy val transformer = createPersistenceTransformerV1(encrKey)
  def persId = stdPersistenceId(regionActorName, actorEntityId)
  def encrKey = {
    val secret = Util.saltedHashedName(actorEntityId + "actor-wallet", appConfig)
    Util.getEventEncKey(secret, appConfig)
  }

  lazy val eventTransformation = persistenceTransformerV1
  lazy val snapshotTransformation = persistenceTransformerV1
  override lazy val persistenceEncryptionKey: String = encrKey
}
