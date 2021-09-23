package com.evernym.verity.actor.agent.snapshot

import com.evernym.verity.actor.PersistentMsg
import com.evernym.verity.actor.testkit.{AgentSpecHelper, PersistentActorSpec}
import com.evernym.verity.actor.testkit.actor.SnapshotSpecBase
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

trait AgentSnapshotSpecBase
  extends SnapshotSpecBase
    with AgentSpecHelper
    with Eventually {
  this: PersistentActorSpec with BasicSpec =>

  def checkSnapshotState(state: StateType, protoInstancesSize: Int): Unit

  def checkPersistentState(expectedPersistedEvents: Int,
                           expectedPersistedSnapshots: Int,
                           protoInstancesSize: Int)
  : Unit = {
    eventually(timeout(Span(5, Seconds)), interval(Span(200, Millis))) {
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
}
