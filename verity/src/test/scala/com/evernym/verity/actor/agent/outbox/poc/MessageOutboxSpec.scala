package com.evernym.verity.actor.agent.outbox.poc

import akka.Done
import akka.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit, TestProbe}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef}
import akka.pattern.StatusReply
import com.evernym.verity.actor.agent.outbox.poc.Message.{MsgId, MsgType}
import com.evernym.verity.actor.agent.outbox.poc.Outbox.OutboxId
import com.evernym.verity.actor.typed.TypedTestKit
import com.evernym.verity.testkit.BasicSpec

/**
  * Tests where both outbox and message actors are used together.
  */
class MessageOutboxSpec
  extends ScalaTestWithActorTestKit(
      ActorTestKit(
        "TestSystem",
        TypedTestKit.config.withFallback(TypedTestKit.clusterConfig)
      ))
    with BasicSpec {

  private val sharding = ClusterSharding(system)

  // registration of Entities needs to happen at startup, probably through the root actor
  // see https://doc.akka.io/docs/akka/current/typed/actor-lifecycle.html#creating-actors
  private val OutboxRegion = sharding.init( Entity (Outbox.TypeKey) { entityContext => Outbox(entityContext.entityId) } )
  private val MessageRegion = sharding.init( Entity (Message.TypeKey) { entityContext => Message(entityContext.entityId) } )

  case class ScenarioResult( probe: TestProbe[StatusReply[Any]],
                             outboxActor: EntityRef[Outbox.Cmd],
                             messageActors: List[EntityRef[Message.Cmd]] )

  /**
    * Helper function to create entity actors for an outbox with the supplied
    * id and messages with the supplied ids and content.
    *
    * @param outboxId
    * @param msgs
    * @return
    *
    * @example
    * setup (
    *   outboxId = "3",
    *   msgs = List (
    *     "cde" -> "Hello, all of my friends!"
    *   )
    * )
    */
  def setup(outboxId: OutboxId, msgs: List[(MsgId,MsgType)]): ScenarioResult = {
    val probe = createTestProbe[StatusReply[Any]]()
    val outboxActor = sharding.entityRefFor(Outbox.TypeKey, outboxId)

    val messageActors = msgs map { case (msgId, msg) =>
      // add message
      val messageActor = sharding.entityRefFor(Message.TypeKey, msgId)
      messageActor ! Message.Cmd.Set(msg, probe.ref)
      probe.expectMessage(StatusReply.Ack)

      // update outbox
      outboxActor ! Outbox.Cmd.AddMsg(msgId, probe.ref)
      probe.expectMessage(StatusReply.Ack)
      messageActor
    }
    ScenarioResult(probe, outboxActor, messageActors)
  }


  "An Outbox region" - {
    "responds to a command in a ShardingEnvelope" in {
      val probe = createTestProbe[StatusReply[Done]]()
      val outboxId = "1"

      OutboxRegion ! ShardingEnvelope(outboxId, Outbox.Cmd.AddMsg("abc", probe.ref))
      probe.expectMessage(StatusReply.Ack)
    }
  }

  "A Message region" - {
    "responds to a command in a ShardingEnvelope" in {
      val probe = createTestProbe[StatusReply[Done]]()

      MessageRegion ! ShardingEnvelope("abc", Message.Cmd.Set("Hello, my friends!", probe.ref))
      probe.expectMessage(StatusReply.Ack)
    }
  }

  "A sharded outbox" - {
    "can be accessed with entityRefFor" - {
      val probe = createTestProbe[StatusReply[Done]]()

      val outbox1 = sharding.entityRefFor(Outbox.TypeKey, "2")
      outbox1 ! Outbox.Cmd.AddMsg("bcd", probe.ref)
      probe.expectMessage(StatusReply.Ack)
    }
  }

  "A sharded message" - {
    "can be accessed with entityRefFor" - {
      val probe = createTestProbe[StatusReply[Done]]()

      val message = sharding.entityRefFor(Message.TypeKey, "bcd")
      message ! Message.Cmd.Set("Hello, my other friends!", probe.ref)
      probe.expectMessage(StatusReply.Ack)
    }
  }

  "With a single message with a single outbox" - {
    "the outbox" - {
      "when sent GetMsgs" - {
        "causes Message to be returned" in {
          val ScenarioResult(probe, outboxActor, _) = setup (
              outboxId = "3",
              msgs = List (
                "cde" -> "Hello, all of my friends!"
              )
            )
          outboxActor ! Outbox.Cmd.GetMsgs(replyTo=probe.ref)
          probe expectMessage {
            StatusReply.success(
              Outbox.Reply.Msgs(
                Vector(
                  Message.Reply.Msg("cde", "Hello, all of my friends!")
                ),
                1
              )
            )
          }
        }
      }
    }
  }
  "A collection of messages with a single outbox" - {
    "when sent GetMsgs" - {
      "responds with messages in the correct order" in {
        val ScenarioResult(probe, outboxActor, _) = setup (
          outboxId = "4",
          msgs = List (
            "4-1" -> "Hello, all of my friends! 1",
            "4-2" -> "Hello, all of my friends! 2",
            "4-3" -> "Hello, all of my friends! 3",
            "4-4" -> "Hello, all of my friends! 4"
          )
        )

        outboxActor ! Outbox.Cmd.GetMsgs(replyTo=probe.ref)
        val reply = probe.receiveMessage()
        reply shouldBe {
          StatusReply.success(
            Outbox.Reply.Msgs(
              Vector(
                Message.Reply.Msg("4-1", "Hello, all of my friends! 1"),
                Message.Reply.Msg("4-2", "Hello, all of my friends! 2"),
                Message.Reply.Msg("4-3", "Hello, all of my friends! 3"),
                Message.Reply.Msg("4-4", "Hello, all of my friends! 4")
              ),
              4
            )
          )
        }
        // TODO check that aggregator actor is stopped after sending the result
      }
      "and no more messages than outbox max" in {
        // assumes outbox is configured to send a max of 10 messages
        val ScenarioResult(probe, outboxActor, _) = setup (
          outboxId = "5",
          msgs = List (
            "5-1" -> "Hello, all of my friends! 1",
            "5-2" -> "Hello, all of my friends! 2",
            "5-3" -> "Hello, all of my friends! 3",
            "5-4" -> "Hello, all of my friends! 4",
            "5-5" -> "Hello, all of my friends! 5",
            "5-6" -> "Hello, all of my friends! 6",
            "5-7" -> "Hello, all of my friends! 7",
            "5-8" -> "Hello, all of my friends! 8",
            "5-9" -> "Hello, all of my friends! 9",
            "5-10" -> "Hello, all of my friends! 10",
            "5-11" -> "Hello, all of my friends! 11",
            "5-12" -> "Hello, all of my friends! 12"
          )
        )

        outboxActor ! Outbox.Cmd.GetMsgs(replyTo=probe.ref)
        val reply = probe.receiveMessage()
        reply shouldBe {
          StatusReply.success(
            Outbox.Reply.Msgs(
              Vector(
                Message.Reply.Msg("5-1", "Hello, all of my friends! 1"),
                Message.Reply.Msg("5-2", "Hello, all of my friends! 2"),
                Message.Reply.Msg("5-3", "Hello, all of my friends! 3"),
                Message.Reply.Msg("5-4", "Hello, all of my friends! 4"),
                Message.Reply.Msg("5-5", "Hello, all of my friends! 5"),
                Message.Reply.Msg("5-6", "Hello, all of my friends! 6"),
                Message.Reply.Msg("5-7", "Hello, all of my friends! 7"),
                Message.Reply.Msg("5-8", "Hello, all of my friends! 8"),
                Message.Reply.Msg("5-9", "Hello, all of my friends! 9"),
                Message.Reply.Msg("5-10", "Hello, all of my friends! 10")
              ),
              10
            )
          )
        }
      }
      "with max count indicated in GetMsg" - {
        "responds with no more than max count" in {
          val ScenarioResult(probe, outboxActor, _) = setup (
            outboxId = "6",
            msgs = List (
              "6-1" -> "Hello, all of my friends! 1",
              "6-2" -> "Hello, all of my friends! 2",
              "6-3" -> "Hello, all of my friends! 3",
              "6-4" -> "Hello, all of my friends! 4"
            )
          )

          // include a max value of 2 in the GetMsgs command
          outboxActor ! Outbox.Cmd.GetMsgs(replyTo=probe.ref, Some(2))
          val reply = probe.receiveMessage()
          reply shouldBe {
            StatusReply.success(
              Outbox.Reply.Msgs(
                Vector(
                  Message.Reply.Msg("6-1", "Hello, all of my friends! 1"),
                  Message.Reply.Msg("6-2", "Hello, all of my friends! 2")
                ),
                2
              )
            )
          }
        }
        "and max count exceeds outbox max" - {
          "responds with no more than outbox max" in {
            // assumes outbox is configured to send a max of 10 messages
            val ScenarioResult(probe, outboxActor, _) = setup (
              outboxId = "7",
              msgs = List (
                "7-1" -> "Hello, all of my friends! 1",
                "7-2" -> "Hello, all of my friends! 2",
                "7-3" -> "Hello, all of my friends! 3",
                "7-4" -> "Hello, all of my friends! 4",
                "7-5" -> "Hello, all of my friends! 5",
                "7-6" -> "Hello, all of my friends! 6",
                "7-7" -> "Hello, all of my friends! 7",
                "7-8" -> "Hello, all of my friends! 8",
                "7-9" -> "Hello, all of my friends! 9",
                "7-10" -> "Hello, all of my friends! 10",
                "7-11" -> "Hello, all of my friends! 11",
                "7-12" -> "Hello, all of my friends! 12"
              )
            )

            // include a max value of 13 in the GetMsgs command
            outboxActor ! Outbox.Cmd.GetMsgs(replyTo=probe.ref, Some(13))

            val reply = probe.receiveMessage()
            reply shouldBe {
              StatusReply.success(
                Outbox.Reply.Msgs(
                  Vector(
                    Message.Reply.Msg("7-1", "Hello, all of my friends! 1"),
                    Message.Reply.Msg("7-2", "Hello, all of my friends! 2"),
                    Message.Reply.Msg("7-3", "Hello, all of my friends! 3"),
                    Message.Reply.Msg("7-4", "Hello, all of my friends! 4"),
                    Message.Reply.Msg("7-5", "Hello, all of my friends! 5"),
                    Message.Reply.Msg("7-6", "Hello, all of my friends! 6"),
                    Message.Reply.Msg("7-7", "Hello, all of my friends! 7"),
                    Message.Reply.Msg("7-8", "Hello, all of my friends! 8"),
                    Message.Reply.Msg("7-9", "Hello, all of my friends! 9"),
                    Message.Reply.Msg("7-10", "Hello, all of my friends! 10")
                  ),
                  10
                )
              )
            }
          }
        }
      }
    }
    "when sent GetMsgs again without confirming msgs received" - {
      "sends the same messages again" in {
        val ScenarioResult(probe, outboxActor, _) = setup (
          outboxId = "8",
          msgs = List (
            "8-1" -> "Hello, all of my friends! 1",
            "8-2" -> "Hello, all of my friends! 2",
            "8-3" -> "Hello, all of my friends! 3",
          )
        )

        outboxActor ! Outbox.Cmd.GetMsgs(replyTo=probe.ref)

        val reply1 = probe.receiveMessage()
        reply1 shouldBe {
          StatusReply.success(
            Outbox.Reply.Msgs(
              Vector(
                Message.Reply.Msg("8-1", "Hello, all of my friends! 1"),
                Message.Reply.Msg("8-2", "Hello, all of my friends! 2"),
                Message.Reply.Msg("8-3", "Hello, all of my friends! 3")
              ),
              3
            )
          )
        }

        outboxActor ! Outbox.Cmd.GetMsgs(replyTo=probe.ref)

        val reply2 = probe.receiveMessage()
        reply2 shouldBe {
          StatusReply.success(
            Outbox.Reply.Msgs(
              Vector(
                Message.Reply.Msg("8-1", "Hello, all of my friends! 1"),
                Message.Reply.Msg("8-2", "Hello, all of my friends! 2"),
                Message.Reply.Msg("8-3", "Hello, all of my friends! 3")
              ),
              3
            )
          )
        }

      }
    }
    "when sent Received command" - {
      "does not send messages marked as received" in {

        val ScenarioResult(probe, outboxActor, _) = setup (
          outboxId = "9",
          msgs = List (
            "9-1" -> "Hello, all of my friends! 1",
            "9-2" -> "Hello, all of my friends! 2",
            "9-3" -> "Hello, all of my friends! 3",
            "9-4" -> "Hello, all of my friends! 4"
          )
        )

        outboxActor ! Outbox.Cmd.Received(2, replyTo=probe.ref)
        probe.expectMessage(StatusReply.Ack)

        outboxActor ! Outbox.Cmd.GetMsgs(replyTo=probe.ref)

        val reply = probe.receiveMessage()
        reply shouldBe {
          StatusReply.success(
            Outbox.Reply.Msgs(
              Vector(
                Message.Reply.Msg("9-3", "Hello, all of my friends! 3"),
                Message.Reply.Msg("9-4", "Hello, all of my friends! 4")
              ),
              4
            )
          )
        }
      }
    }
  }
}
