package com.evernym.verity.protocol.protocols

import akka.actor.{ActorRef, Props}
import akka.util.ByteString
import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.actor.testkit.checks.{UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.testkit.BasicSpec

import scala.collection.immutable
import scala.language.postfixOps

// This is taken from Akka documentation. FSM functionality seems to be
// possible with Akka Behaviors, and for persistence at least, Akka is
// recommending using Akka Typed. Akka Typed is very new, though the
// Lightbend team report that it's pretty good.
//
// The reason for including this is that it has an interesting DSL for
// state transitions that we might want to learn from.


object FSMDocSpec {
  // received events
  final case class SetTarget(ref: ActorRef) extends ActorMessage
  final case class Queue(obj: Any) extends ActorMessage
  case object Flush extends ActorMessage

  // sent events
  final case class Batch(obj: immutable.Seq[Any]) extends ActorMessage

  // states
  sealed trait State
  case object Idle extends State
  case object Active extends State

  sealed trait Data
  case object Uninitialized extends Data
  final case class Todo(target: ActorRef, queue: immutable.Seq[Any]) extends Data
}



class FSMDocSpec extends ActorSpec with BasicSpec {

  import FSMDocSpec._
  import akka.actor.FSM

  import scala.concurrent.duration._

  class Buncher extends FSM[State, Data] {

    startWith(Idle, Uninitialized)

    when(Idle) {
      case Event(SetTarget(ref), Uninitialized) =>
        stay using Todo(ref, Vector.empty)
    }

    onTransition {
      case Active -> Idle =>
        stateData match {
          case Todo(ref, queue) => ref ! Batch(queue)
          case _ => // nothing to do
        }
    }

    when(Active, stateTimeout = 1 second) {
      case Event(Flush | StateTimeout, t: Todo) =>
        goto(Idle) using t.copy(queue = Vector.empty)
    }

    whenUnhandled {
      // common code for both states
      case Event(Queue(obj), t @ Todo(_, v)) =>
        goto(Active) using t.copy(queue = v :+ obj)

      case Event(e, s) =>
        log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
        stay
    }

    initialize()
  }
  object DemoCode {
    trait StateType
    case object SomeState extends StateType
    case object Processing extends StateType
    case object Error extends StateType
    case object Idle extends StateType
    case object Active extends StateType

    class Dummy extends FSM[StateType, Int] {
      class X
      val newData = 42
      object WillDo
      object Tick

      when(SomeState) {
        case Event(msg, _) =>
          goto(Processing) using (newData) forMax (5 seconds) replying (WillDo)
      }

      onTransition {
        case Idle -> Active => startTimerWithFixedDelay("timeout", Tick, 1 second)
        case Active -> _ => cancelTimer("timeout")
        case x -> Idle => log.info("entering Idle from " + x)
      }

      onTransition(handler _)

      def handler(from: StateType, to: StateType) {
        // handle it here ...
      }

      when(Error) {
        case Event("stop", _) =>
          // do cleanup ...
          stop()
      }

      when(SomeState)(transform {
        case Event(bytes: ByteString, read) => stay using (read + bytes.length)
      } using {
        case s @ FSM.State(state, read, timeout, stopReason, replies) if read > 1000 =>
          goto(Processing)
      })

      val processingTrigger: PartialFunction[State, State] = {
        case s @ FSM.State(state, read, timeout, stopReason, replies) if read > 1000 =>
          goto(Processing)
      }

      when(SomeState)(transform {
        case Event(bytes: ByteString, read) => stay using (read + bytes.length)
      } using processingTrigger)

      onTermination {
        case StopEvent(FSM.Normal, state, data) => // ...
        case StopEvent(FSM.Shutdown, state, data) => // ...
        case StopEvent(FSM.Failure(cause), state, data) => // ...
      }

      whenUnhandled {
        case Event(x: X, data) =>
          log.info("Received unhandled event: " + x)
          stay
        case Event(msg, _) =>
          log.warning("Received unknown event: " + msg)
          goto(Error)
      }

    }

    import akka.actor.LoggingFSM
    class MyFSM extends LoggingFSM[StateType, Data] {
      override def logDepth = 12
      onTermination {
        case StopEvent(FSM.Failure(_), state, data) =>
          val lastEvents = getLog.mkString("\n\t")
          log.warning("Failure in state " + state + " with data " + data + "\n" +
            "Events leading up to this point:\n\t" + lastEvents)
      }
      // ...
    }

  }

  "simple finite state machine" - {

    "demonstrate NullFunction" in {
      class A extends FSM[Int, Null] {
        val SomeState = 0
        when(SomeState)(FSM.NullFunction)
      }
    }

    "batch correctly" in {
      val buncher = system.actorOf(Props(classOf[Buncher], this))
      buncher ! SetTarget(testActor)
      buncher ! Queue(42)
      buncher ! Queue(43)
      expectMsg(Batch(immutable.Seq(42, 43)))
      buncher ! Queue(44)
      buncher ! Flush
      buncher ! Queue(45)
      expectMsg(Batch(immutable.Seq(44)))
      expectMsg(Batch(immutable.Seq(45)))
    }

    "not batch if uninitialized" taggedAs (UNSAFE_IgnoreAkkaEvents, UNSAFE_IgnoreLog) in {
      val buncher = system.actorOf(Props(classOf[Buncher], this))
      buncher ! Queue(42)
      expectNoMessage
    }
  }
}
