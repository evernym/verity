package com.evernym.verity.actor.agent.outbox

import akka.Done
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import akka.pattern.StatusReply

object AccountEntity {
  // Command
  sealed trait Command extends Encodable
  final case class CreateAccount(replyTo: ActorRef[StatusReply[Done]]) extends Command
  final case class Deposit(amount: BigDecimal, replyTo: ActorRef[StatusReply[Done]]) extends Command
  final case class Withdraw(amount: BigDecimal, replyTo: ActorRef[StatusReply[Done]]) extends Command
  final case class GetBalance(replyTo: ActorRef[CurrentBalance]) extends Command
  final case class CloseAccount(replyTo: ActorRef[StatusReply[Done]]) extends Command

  // Reply
  final case class CurrentBalance(balance: BigDecimal) extends Encodable

  // Event
  sealed trait Event extends Encodable
  case object AccountCreated extends Event
  case class Deposited(amount: BigDecimal) extends Event
  case class Withdrawn(amount: BigDecimal) extends Event
  case object AccountClosed extends Event

  val Zero = BigDecimal(0)

  // State
  sealed trait Account extends Encodable {
    def applyEvent(event: Event): Account
  }
  case object EmptyAccount extends Account {
    override def applyEvent(event: Event): Account = event match {
      case AccountCreated => OpenedAccount(Zero)
      case _              => throw new IllegalStateException(s"unexpected event [$event] in state [EmptyAccount]")
    }
  }
  case class OpenedAccount(balance: BigDecimal) extends Account {
    require(balance >= Zero, "Account balance can't be negative")

    override def applyEvent(event: Event): Account =
      event match {
        case Deposited(amount) => copy(balance = balance + amount)
        case Withdrawn(amount) => copy(balance = balance - amount)
        case AccountClosed     => ClosedAccount
        case AccountCreated    => throw new IllegalStateException(s"unexpected event [$event] in state [OpenedAccount]")
      }

    def canWithdraw(amount: BigDecimal): Boolean = {
      balance - amount >= Zero
    }

  }
  case object ClosedAccount extends Account {
    override def applyEvent(event: Event): Account =
      throw new IllegalStateException(s"unexpected event [$event] in state [ClosedAccount]")
  }

  // when used with sharding, this TypeKey can be used in `sharding.init` and `sharding.entityRefFor`:
  val TypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command]("Account")

  // Note that after defining command, event and state classes you would probably start here when writing this.
  // When filling in the parameters of EventSourcedBehavior.apply you can use IntelliJ alt+Enter > createValue
  // to generate the stub with types for the command and event handlers.

  def apply(accountNumber: String, persistenceId: PersistenceId): Behavior[Command] = {
    EventSourcedBehavior.withEnforcedReplies(persistenceId, EmptyAccount, commandHandler(accountNumber), eventHandler)
  }

  private def commandHandler(accountNumber: String): (Account, Command) => ReplyEffect[Event, Account] = {
    (state, cmd) =>
      state match {
        case EmptyAccount =>
          cmd match {
            case c: CreateAccount => createAccount(c)
            case _                => Effect.unhandled.thenNoReply() // CreateAccount before handling any other commands
          }

        case acc @ OpenedAccount(_) =>
          cmd match {
            case c: Deposit      => deposit(c)
            case c: Withdraw     => withdraw(acc, c)
            case c: GetBalance   => getBalance(acc, c)
            case c: CloseAccount => closeAccount(acc, c)
            case c: CreateAccount =>
              Effect.reply(c.replyTo)(StatusReply.Error(s"Account $accountNumber is already created"))
          }

        case ClosedAccount =>
          cmd match {
            case c: Deposit =>
              replyClosed(accountNumber, c.replyTo)
            case c: Withdraw =>
              replyClosed(accountNumber, c.replyTo)
            case GetBalance(replyTo) =>
              Effect.reply(replyTo)(CurrentBalance(Zero))
            case CloseAccount(replyTo) =>
              replyClosed(accountNumber, replyTo)
            case CreateAccount(replyTo) =>
              replyClosed(accountNumber, replyTo)
          }
      }
  }

  private def replyClosed(
                           accountNumber: String,
                           replyTo: ActorRef[StatusReply[Done]]): ReplyEffect[Event, Account] = {
    Effect.reply(replyTo)(StatusReply.Error(s"Account $accountNumber is closed"))
  }

  private val eventHandler: (Account, Event) => Account = { (state, event) =>
    state.applyEvent(event)
  }

  private def createAccount(cmd: CreateAccount): ReplyEffect[Event, Account] = {
    Effect.persist(AccountCreated).thenReply(cmd.replyTo)(_ => StatusReply.Ack)
  }

  private def deposit(cmd: Deposit): ReplyEffect[Event, Account] = {
    Effect.persist(Deposited(cmd.amount)).thenReply(cmd.replyTo)(_ => StatusReply.Ack)
  }

  private def withdraw(acc: OpenedAccount, cmd: Withdraw): ReplyEffect[Event, Account] = {
    if (acc.canWithdraw(cmd.amount))
      Effect.persist(Withdrawn(cmd.amount)).thenReply(cmd.replyTo)(_ => StatusReply.Ack)
    else
      Effect.reply(cmd.replyTo)(
        StatusReply.Error(s"Insufficient balance ${acc.balance} to be able to withdraw ${cmd.amount}"))
  }

  private def getBalance(acc: OpenedAccount, cmd: GetBalance): ReplyEffect[Event, Account] = {
    Effect.reply(cmd.replyTo)(CurrentBalance(acc.balance))
  }

  private def closeAccount(acc: OpenedAccount, cmd: CloseAccount): ReplyEffect[Event, Account] = {
    if (acc.balance == Zero)
      Effect.persist(AccountClosed).thenReply(cmd.replyTo)(_ => StatusReply.Ack)
    else
      Effect.reply(cmd.replyTo)(StatusReply.Error("Can't close account with non-zero balance"))
  }

}