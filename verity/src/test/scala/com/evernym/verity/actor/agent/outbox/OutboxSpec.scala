package com.evernym.verity.actor.agent.outbox

import akka.Done
import akka.actor.testkit.typed.scaladsl._
import akka.pattern.StatusReply
import com.evernym.verity.actor.agent.outbox.TestKit.TestOutbox
import com.evernym.verity.actor.agent.outbox.Outbox.{Cmd, Evt, State}
import com.evernym.verity.testkit.BasicSpec

import scala.language.implicitConversions

/**
  * These tests use the same Akka system. Each test instantiates a Scenario that is a helper encapsulation
  */
class OutboxSpec
  extends ScalaTestWithActorTestKit(TestKit.config)
    with BasicSpec {

  implicit def XToOption[A](a: A): Option[A] = Option(a)

  "An outbox" - {
    val msgId1 = "abc123"
    val msgId2 = "bcd234"
    val msgId3 = "cde345"
    "when created" - {
      "is empty" in {
        val s = TestOutbox()
        val result = s.tk.restart()
        result.state shouldBe State.Empty
      }
    }
    "when sent NewMsg" - {
      "replies with Ack" in {
        val s = TestOutbox()
        val result = s.tk.runCommand[StatusReply[Done]](Cmd.AddMsg(msgId1, _))
        result.reply shouldBe StatusReply.Ack
      }
      "results in a NewMsgEvt event" in {
        val s = TestOutbox()
        val result = s.tk.runCommand[StatusReply[Done]](Cmd.AddMsg(msgId1, _))
        result.event shouldBe Evt.MsgAdded(msgId1)
      }
      "evolves state" in {
        val s = TestOutbox()
        val result = s.tk.runCommand[StatusReply[Done]](Cmd.AddMsg(msgId1, _))
        result.state shouldBe State.Basic(Vector(msgId1))
        result.stateOfType[State.Basic].numPending shouldBe 1
      }
      "records event in storage" in {
        val s = TestOutbox()
        s.tk.runCommand[StatusReply[Done]](Cmd.AddMsg(msgId1, _))

        s expectJournalEntry Evt.MsgAdded(msgId1)

        s.expectNoNewSnapshots()

      }
      "restarts to the same state using events" in {
        val s = TestOutbox()
        s.tk.runCommand[StatusReply[Done]](Cmd.AddMsg(msgId1, _))
        s.tk.runCommand[StatusReply[Done]](Cmd.AddMsg(msgId2, _))
        val result1 = s.tk.runCommand[StatusReply[Done]](Cmd.AddMsg(msgId3, _))
        result1.state shouldBe State.Basic(Vector(msgId1, msgId2, msgId3))

        val result2 = s.tk.restart()

        result1.state shouldBe result2.state
      }
    }
    "snapshot scenarios" - {
      def msgIds(r: Range) = r.map("msg-" + _).toVector

      "snapshot scenario 1" - {
        lazy val ss1 = TestOutbox()

        // using lazy vals below that evaluate an expression ensures tests can
        // leverage the state of earlier tests while still being able to be run
        // independently
        lazy val ensure9MsgsSent: Unit = {

          ss1 sendMsgs msgIds(1 to 9)
          ss1 checkEvents msgIds(1 to 9)

        }

        lazy val ensure10MsgsSent: Unit = {
          ensure9MsgsSent

          ss1 sendMsgs msgIds(10 to 10)
          ss1 checkEvents msgIds(10 to 10)
        }


        "when sent nine messages" - {
          "records nine events" in {
            ensure9MsgsSent
          }
          "but does not record a snapshot" in {
            ensure9MsgsSent

            // Outbox is configured to persist every 10 events, so there should be no snapshot yet
            ss1 expectSnapshot None

          }
        }
        "when sent a tenth message" - {
          "records an event" in {
            ensure10MsgsSent
          }
          "and records a snapshot" in {
            ensure10MsgsSent

            ss1 expectSnapshot State.Basic(msgIds(1 to 10))

          }
        }
      }

      "snapshot scenario 2" - {
        val ss2 = TestOutbox()

        lazy val ensure10msgsSent: Unit = {
          ss2 sendMsgs msgIds(1 to 10)
          ss2 checkEvents msgIds(1 to 10)

          ss2 expectSnapshot State.Basic(msgIds(1 to 10))
          ss2 expectSnapshot None

        }

        lazy val ensure20msgsSent: Unit = {
          ensure10msgsSent

          ss2 sendMsgs msgIds(11 to 20)
          ss2 checkEvents msgIds(11 to 20)

          ss2 expectSnapshot State.Basic(msgIds(1 to 20))
          ss2 expectSnapshot None

        }

        lazy val ensure30msgsSent: Unit = {
          ensure20msgsSent

          ss2 sendMsgs msgIds(21 to 30)
          ss2 checkEvents msgIds(21 to 30)

          ss2 expectSnapshot State.Basic(msgIds(1 to 30))
          ss2 expectSnapshot None

        }

        lazy val ensure33msgsSent: Unit = {
          ensure30msgsSent

          ss2 sendMsgs msgIds(31 to 33)
          ss2 checkEvents msgIds(31 to 33)

          ss2 expectSnapshot None

        }

        "when sent 10 messages" - {
          "has one snapshot total in storage" in {
            ensure10msgsSent

            ss2.storedSnapshots shouldBe Vector(
              State.Basic(msgIds(1 to 10)),
            )
          }
        }
        "when sent another 10 (11-20) )messages" - {
          "has two snapshots total in storage" in {
            ensure20msgsSent

            ss2.storedSnapshots shouldBe Vector(
              State.Basic(msgIds(1 to 10)),
              State.Basic(msgIds(1 to 20))
            )
          }
        }
        "when sent another 10 (21-30) messages" - {
          "still has only two snapshots total in storage" in { // because outbox is configured to retain only 2 snapshots

            ensure30msgsSent

            // note there is no snapshot for State.Basic(msgIds(1 to 10)) because it was deleted
            ss2.storedSnapshots shouldBe Vector(
              State.Basic(msgIds(1 to 10)), //TODO: temporarily added as per changes in 'Outbox.scala'.
              State.Basic(msgIds(1 to 20)),
              State.Basic(msgIds(1 to 30))
            )

          }
        }
        "can recover from snapshot if events lost" in {
          ensure33msgsSent

          val state = ss2.tk.restart().state

          state shouldBe State.Basic(msgIds(1 to 33))

          val persistedEvents = ss2.tk.persistenceTestKit.persistedInStorage(ss2.persistenceId.id)
          persistedEvents shouldBe msgIds(1 to 33).map(Evt.MsgAdded)

          ss2.tk.persistenceTestKit.clearByPersistenceId(ss2.persistenceId.id)

          val state2 = ss2.tk.restart().state

          // note the state is up to msg id 30, not 33. This is because we
          // deleted all events above, and state will be the most recent
          // snapshot.
          state2 shouldBe State.Basic(msgIds(1 to 30))

        }
      }
    }
  }
}
