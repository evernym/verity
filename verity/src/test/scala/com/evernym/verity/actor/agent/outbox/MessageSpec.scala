package com.evernym.verity.actor.agent.outbox

import akka.Done
import akka.actor.testkit.typed.scaladsl._
import akka.pattern.StatusReply
import com.evernym.verity.actor.agent.outbox.TestKit.TestMessage
import com.evernym.verity.actor.agent.outbox.Message.Error.{MSG_ALREADY_SET, MSG_CLEARED, MSG_NOT_SET}
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.BeforeAndAfterEach

import scala.language.implicitConversions


/**
  * These tests use the same Akka system. Each test instantiates a Scenario that is a helper encapsulation
  */
class MessageSpec
  extends ScalaTestWithActorTestKit(TestKit.config)
    with BasicSpec
    with BeforeAndAfterEach {


  implicit def XToOption[A](a: A): Option[A] = Option(a)


  "A Message Actor" - {
    "when created" - {
      "is empty" in {
        val s = TestMessage()

        // start an actor with no history
        val result = s.tk.restart()
        result.state shouldBe Message.State.Empty
      }
    }
    "handles Set command" - {
      "when empty" in {
        val s = TestMessage()
        val msg = "hello"

        // set message
        val result = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Set(msg, _))
        result.reply shouldBe StatusReply.Ack
        result.event shouldBe Message.Evt.Set(msg)
        result.state shouldBe Message.State.Msg(msg)
      }
      "when message already set" in {
        val s = TestMessage()
        val msg = "hello"

        // set message
        val result1 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Set(msg, _))

        // attempt to set message again
        val result2 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Set(msg, _))
        result2.reply shouldBe StatusReply.Error(MSG_ALREADY_SET)
        result2.hasNoEvents shouldBe true
        result2.state shouldBe result1.state
      }
      "when message is cleared" in {
        val s = TestMessage()
        val msg = "hello"

        // set message
        val result1 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Set(msg, _))

        // clear message
        val result2 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Clear(_))
        result2.reply shouldBe StatusReply.Ack
        result2.state shouldBe Message.State.Cleared

        // attempt to set message again
        val result3 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Set(msg, _))
        result3.reply shouldBe StatusReply.Error(MSG_CLEARED)
        result3.hasNoEvents shouldBe true
      }
    }
    "handles GetMsg" - {
      "when message is empty" in {
        val s = TestMessage()

        // attempt to get empty message
        val result = s.tk.runCommand[StatusReply[Message.Reply.Msg]](Message.Cmd.Get(_))
        result.reply shouldBe StatusReply.Error(MSG_NOT_SET)
        result.hasNoEvents shouldBe true
      }
      "when message is set" in {
        val s = TestMessage()
        val msg = "hello"

        // set message
        s.tk.runCommand[StatusReply[Done]](Message.Cmd.Set(msg, _))

        // get message
        val result = s.tk.runCommand[StatusReply[Message.Reply.Msg]](Message.Cmd.Get(_))
        result.reply should be {
          StatusReply.success(
            Message.Reply.Msg(s.entityId, "hello")
          )
        }
      }
      "when message is cleared" in {
        val s = TestMessage()
        val msg = "hello"

        // set message
        val result1 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Set(msg, _))

        // clear message
        val result2 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Clear(_))
        result2.reply shouldBe StatusReply.Ack
        result2.state shouldBe Message.State.Cleared

        // attempt to clear message again
        val result3 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Clear(_))
        result3.reply shouldBe StatusReply.Error(MSG_CLEARED)
        result3.hasNoEvents shouldBe true
      }
    }
    "handles ClearMsg" - {
      "when message is empty" in {
        val s = TestMessage()

        // attempt to clear empty message
        val result = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Clear(_))
        result.reply shouldBe StatusReply.Error(MSG_NOT_SET)
        result.hasNoEvents shouldBe true
      }
      "when message is set" in {
        val s = TestMessage()
        val msg = "hello"

        // set message
        val result1 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Set(msg, _))
        result1.reply shouldBe StatusReply.Ack
        result1.event shouldBe Message.Evt.Set(msg)
        result1.state shouldBe Message.State.Msg(msg)

        // clear message
        val result2 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Clear(_))
        result2.reply shouldBe StatusReply.Ack
        result2.event shouldBe Message.Evt.Cleared
        result2.state shouldBe Message.State.Cleared
      }
      "when message is already cleared" in {
        val s = TestMessage()
        val msg = "hello"

        // set message
        val result1 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Set(msg, _))

        // clear message
        val result2 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Clear(_))
        result2.reply shouldBe StatusReply.Ack
        result2.event shouldBe Message.Evt.Cleared
        result2.state shouldBe Message.State.Cleared

        // attempt to clear again
        val result3 = s.tk.runCommand[StatusReply[Done]](Message.Cmd.Clear(_))
        result3.reply shouldBe StatusReply.Error(MSG_CLEARED)
        result3.hasNoEvents shouldBe true
        result3.state shouldBe Message.State.Cleared

      }
    }
  }
}