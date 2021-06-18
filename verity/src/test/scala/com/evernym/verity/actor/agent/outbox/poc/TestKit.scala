package com.evernym.verity.actor.agent.outbox.poc

import akka.Done
import akka.actor.typed.{ActorSystem, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.pattern.StatusReply
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import akka.persistence.testkit.scaladsl.{EventSourcedBehaviorTestKit, PersistenceTestKit, SnapshotTestKit}
import akka.persistence.typed.PersistenceId
import com.evernym.verity.actor.agent.outbox.poc.Message.MsgId
import com.evernym.verity.actor.agent.outbox.poc.Outbox.{Cmd, Evt, State, TypeKey}
import com.typesafe.config.{Config, ConfigFactory}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable
import scala.collection.immutable.IndexedSeq

object TestKit {

  object TestOutbox {
    val lastOutboxId = new AtomicInteger(0)

    def nextOutboxId: String = lastOutboxId.incrementAndGet().toString

    def apply(entityId: String = nextOutboxId)(implicit system: ActorSystem[_]) = new TestOutbox(entityId)
  }

  class TestOutbox(entityId: String)(implicit system: ActorSystem[_])
    extends EventSourcedScenario[Cmd, Evt, State](Outbox(entityId), entityId, TypeKey) {

    def sendMsgs(msgIds: IndexedSeq[MsgId]): Unit = {
      msgIds foreach { msgId =>
        tk.runCommand[StatusReply[Done]](Cmd.AddMsg(msgId, _))
      }
    }

    def checkEvents(msgIds: IndexedSeq[MsgId]): Unit = {
      msgIds foreach { msgId =>
        expectJournalEntry(Evt.MsgAdded(msgId))
      }
    }

  }


  object TestMessage {
    val lastMsgId = new AtomicInteger(0)

    def nextMsgId: String = lastMsgId.incrementAndGet().toString

    def apply(entityId: String = nextMsgId)(implicit system: ActorSystem[_]) = new TestMessage(entityId)
  }

  class TestMessage(entityId: String)(implicit system: ActorSystem[_])
    extends EventSourcedScenario[Message.Cmd, Message.Evt, Message.State](Message(entityId), entityId, Message.TypeKey)


  /**
   * While this is a helper for tests and has value on its own merits, it also somewhat accomplishes the goal of
   * https://github.com/akka/akka/issues/29143.
   */
  abstract class EventSourcedScenario[C, E, S](val behavior: Behavior[C],
                                               val entityId: String,
                                               val TypeKey: EntityTypeKey[C])
                                              (implicit val system: ActorSystem[_])
    extends ScenarioLike[C, E, S]


  trait ScenarioLike[C, E, S] {
    def behavior: Behavior[C]

    def entityId: String

    def TypeKey: EntityTypeKey[C]

    def system: ActorSystem[_]

    lazy val persistenceId: PersistenceId = PersistenceId(TypeKey.name, entityId)
    lazy val tk: EventSourcedBehaviorTestKit[C, E, S] =
      EventSourcedBehaviorTestKit(system, behavior)
    val sstk: SnapshotTestKit = SnapshotTestKit(system)
    lazy val journal: PersistenceTestKit = tk.persistenceTestKit

    def expectJournalEntry[A](event: A): A = journal.expectNextPersisted(persistenceId.id, event)

    def expectSnapshot[A](state: Option[A]): Option[A] = {
      state match {
        case None => sstk.expectNothingPersisted(persistenceId.id); None
        case s@Some(state) => sstk.expectNextPersisted(persistenceId.id, state); s
      }
    }

    def expectNoNewSnapshots(): Unit = sstk.expectNothingPersisted(persistenceId.id)

    def storedSnapshots: immutable.Seq[Any] = sstk.persistedInStorage(persistenceId.id).map(_._2)

  }

}
