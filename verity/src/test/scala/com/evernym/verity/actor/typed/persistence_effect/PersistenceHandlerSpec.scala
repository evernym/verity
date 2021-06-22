package com.evernym.verity.actor.typed.persistence_effect

import akka.Done
import akka.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, ReplyEffect}
import com.evernym.verity.actor.typed.persistence_effect.Events._
import com.evernym.verity.actor.typed.{Encodable, TypedTestKit}
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.actor.typed.poc.{EventSourcedBehaviorBuilder, PersistenceHandler}
import com.evernym.verity.testkit.BasicSpec
import scalapb.GeneratedMessageCompanion

import java.util.UUID


class PersistenceHandlerSpec
  extends ScalaTestWithActorTestKit(
    ActorTestKit(
      "TestSystem",
      TypedTestKit.config.withFallback(TypedTestKit.clusterConfig)
    ))
    with BasicSpec {

  import Account._

  val sharding = ClusterSharding(system)
  val accountRegion = sharding.init(Entity(Account.TypeKey) { entityContext =>
    Account(entityContext.entityId)
  })

  "Account behaviour" - {

    "when initialized first time" - {
      "should be in Empty state" in {
        val probe = createTestProbe[State]()
        accountRegion ! ShardingEnvelope("account-id-1", Account.Commands.GetState(probe.ref))
        probe.expectMessage(States.Empty)
      }
    }

    "when sent a Open command" - {
      "should change to Opened state" in {
        val entityId = openNewAccount("mock-user")
        getState(entityId) shouldBe States.Opened("mock-user", 0)
      }
    }

    "when restarted" - {
      "should be in same state as earlier (Opened)" in {
        val entityId = openNewAccount("mock-user")
        val prob = createTestProbe[StatusReply[Done]]()
        accountRegion ! ShardingEnvelope(entityId, Commands.Stop(prob.ref))
        prob.expectMessage(StatusReply.Ack)
        val state = getState(entityId)
        state shouldBe States.Opened("mock-user", 0)
      }
    }

    "when credited amount" - {
      "should update the balance" in {
        val entityId = openNewAccount("mock-user")
        val prob = createTestProbe[StatusReply[Done]]()
        accountRegion ! ShardingEnvelope(entityId, Commands.Credit(10, prob.ref))
        prob.expectMessage(StatusReply.Ack)

        val state = getState(entityId)
        state shouldBe States.Opened("mock-user", 10)
      }
    }

    "when debited amount" - {
      "should update the balance" in {
        val entityId = openNewAccount("mock-user")

        val prob = createTestProbe[StatusReply[Done]]()
        accountRegion ! ShardingEnvelope(entityId, Commands.Credit(10, prob.ref))
        prob.expectMessage(StatusReply.Ack)

        accountRegion ! ShardingEnvelope(entityId, Commands.Debit(5, prob.ref))
        prob.expectMessage(StatusReply.Ack)
        val state = getState(entityId)
        state shouldBe States.Opened("mock-user", 5)
      }
    }

    "when closed" - {
      "should update the state" in {
        val entityId = openNewAccount("mock-user", 5)
        val prob = createTestProbe[StatusReply[Done]]()
        accountRegion ! ShardingEnvelope(entityId, Commands.Close(prob.ref))
        prob.expectMessage(StatusReply.Ack)
        val state = getState(entityId)
        state shouldBe States.Closed("mock-user", 5)
      }
    }
  }

  def openNewAccount(name: String,
                     balance: Double = 0): EntityId = {
    val entityId = UUID.randomUUID().toString
    val prob = createTestProbe[StatusReply[Done]]()
    accountRegion ! ShardingEnvelope(entityId, Commands.Open(name, balance, prob.ref))
    prob.expectMessage(StatusReply.Ack)
    entityId
  }

  def getState(accountId: String): State = {
    val prob = createTestProbe[State]()
    accountRegion ! ShardingEnvelope(accountId, Commands.GetState(prob.ref))
    prob.expectMessageType[State]
  }

}

object Account {

  trait Cmd extends Encodable
  object Commands {
    case class Open(name: String, balance: Double, replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    case class Credit(amount: Double, replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    case class Debit(amount: Double, replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    case class Close(replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    case class GetState(replyTo: ActorRef[State]) extends Cmd
    case class Stop(replyTo: ActorRef[StatusReply[Done]]) extends Cmd
  }

  trait State extends Encodable
  object States {
    case object Empty extends State
    case class Opened(name: String, balance: Double) extends State
    case class Closed(name: String, balance: Double) extends State
  }

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Account")

  def apply(entityId: EntityId): Behavior[Cmd] = {
    EventSourcedBehaviorBuilder
      .withPersistenceHandler(PersistenceId(TypeKey.name, entityId), States.Empty, commandHandler, eventHandler)
      .withObjectCodeMapper(TestObjectCodeMapper)
      .build()
  }

  def commandHandler(persistenceHandler: PersistenceHandler): (State, Cmd) => ReplyEffect[Any, State] = {

    case (States.Empty, Commands.Open(name, balance, replyTo)) =>
      persistenceHandler
        .persist(Events.Opened(name, balance))
        .thenReply(replyTo)(_ => StatusReply.Ack)

    case (_:States.Opened, Commands.Credit(amount, replyTo)) =>
      persistenceHandler
        .persist(Events.Credited(amount))
        .thenReply(replyTo)(_ => StatusReply.Ack)

    case (_:States.Opened, Commands.Debit(amount, replyTo)) =>
      persistenceHandler
        .persist(Events.Debited(amount))
        .thenReply(replyTo)(_ => StatusReply.Ack)

    case (_:States.Opened, Commands.Close(replyTo)) =>
      persistenceHandler
        .persist(Events.Closed())
        .thenReply(replyTo)(_ => StatusReply.Ack)

    case (st: State, Commands.GetState(replyTo)) =>
      Effect.reply(replyTo)(st)

    case (_: State, Commands.Stop(replyTo)) =>
      Behaviors.stopped
      Effect.reply(replyTo)(StatusReply.Ack)
  }

  private val eventHandler: (State, Any) => State = {
    case (States.Empty, Events.Opened(name, balance)) => States.Opened(name, balance)
    case (cs:States.Opened, Events.Credited(amount))  => cs.copy(balance = cs.balance + amount)
    case (cs:States.Opened, Events.Debited(amount))   => cs.copy(balance = cs.balance - amount)
    case (st:States.Opened, Events.Closed())          => States.Closed(st.name, st.balance)
  }
}


object TestObjectCodeMapper extends ObjectCodeMapperBase {

  lazy val objectCodeMapping: Map[Int, GeneratedMessageCompanion[_]] = Map (
    1 -> Opened,
    2 -> Credited,
    3 -> Debited,
    4 -> Closed
  )
}