package com.evernym.verity.actor.typed.spec

import akka.Done
import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal}
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.evernym.verity.actor.typed.spec.Events._
import com.evernym.verity.actor.typed.BehaviourSpecBase
import com.evernym.verity.actor.persistence.object_code_mapper.ObjectCodeMapperBase
import com.evernym.verity.actor.typed.base.PersistentEventAdapter
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.testkit.BasicSpec
import com.typesafe.scalalogging.Logger
import scalapb.GeneratedMessageCompanion

import java.util.UUID
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.SALT_EVENT_ENCRYPTION

import scala.concurrent.duration._


class PersistentBehaviourSpec
  extends BehaviourSpecBase
    with BasicSpec {

  import Account._

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
        prob.expectNoMessage(5.seconds)
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

  lazy val sharding: ClusterSharding = ClusterSharding(system)
  lazy val appConfig: AppConfig = new TestAppConfig()
  lazy val accountRegion: ActorRef[ShardingEnvelope[Cmd]] = sharding.init(Entity(Account.TypeKey) { entityContext =>
    Account(entityContext, appConfig)
  })

}

object Account {

  trait Cmd
  object Commands {
    case class Open(name: String, balance: Double, replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    case class Credit(amount: Double, replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    case class Debit(amount: Double, replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    case class Close(replyTo: ActorRef[StatusReply[Done]]) extends Cmd
    case class GetState(replyTo: ActorRef[State]) extends Cmd
    case class Stop(replyTo: ActorRef[StatusReply[Done]]) extends Cmd
  }

  trait State
  object States {
    case object Empty extends State
    case class Opened(name: String, balance: Double) extends State
    case class Closed(name: String, balance: Double) extends State
  }

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("Account")

  def apply(entityContext: EntityContext[Cmd], appConfig: AppConfig): Behavior[Cmd] = {
    val persistenceId = PersistenceId(TypeKey.name, entityContext.entityId)
    val salt = appConfig.getStringReq(SALT_EVENT_ENCRYPTION)
    EventSourcedBehavior
      .withEnforcedReplies(persistenceId, States.Empty, commandHandler, eventHandler)
      .eventAdapter(PersistentEventAdapter(entityContext.entityId, TestObjectCodeMapper, salt))
      .receiveSignal(signalHandler(persistenceId))
  }

  val logger: Logger = getLoggerByClass(getClass)

  private def commandHandler: (State, Cmd) => ReplyEffect[Any, State] = {

    case (States.Empty, Commands.Open(name, balance, replyTo)) =>
      Effect
        .persist(Events.Opened(name, balance))
        .thenReply(replyTo)(_ => StatusReply.Ack)

    case (_:States.Opened, Commands.Credit(amount, replyTo)) =>
      Effect
        .persist(Events.Credited(amount))
        .thenReply(replyTo)(_ => StatusReply.Ack)

    case (_:States.Opened, Commands.Debit(amount, replyTo)) =>
      Effect
        .persist(Events.Debited(amount))
        .thenReply(replyTo)(_ => StatusReply.Ack)

    case (_:States.Opened, Commands.Close(replyTo)) =>
      Effect
        .persist(Events.Closed())
        .thenReply(replyTo)(_ => StatusReply.Ack)

    case (st: State, Commands.GetState(replyTo)) =>
      Effect.reply(replyTo)(st)

    case (_: State, Commands.Stop(_)) =>
      Effect
        .stop()
        .thenNoReply()

  }

  private def signalHandler(persistenceId: PersistenceId): PartialFunction[(State, Signal), Unit] = {
    case (_: State, RecoveryCompleted) =>
      logger.debug(s"[$persistenceId] recovered")
    case (_: State, PostStop) =>
      logger.debug(s"[$persistenceId] behaviour stopped")
  }

  private val eventHandler: (State, Any) => State = {
    case (States.Empty,     Events.Opened(name, balance)) => States.Opened(name, balance)
    case (cs:States.Opened, Events.Credited(amount))      => cs.copy(balance = cs.balance + amount)
    case (cs:States.Opened, Events.Debited(amount))       => cs.copy(balance = cs.balance - amount)
    case (st:States.Opened, Events.Closed())              => States.Closed(st.name, st.balance)
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