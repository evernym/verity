package com.evernym.verity.endorser_registry

import akka.actor.typed.{ActorRef, Behavior, Signal}
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.{PersistenceId, RecoveryCompleted}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import com.evernym.verity.actor.{ActorMessage, RetentionCriteriaBuilder}
import com.evernym.verity.actor.typed.base.{PersistentEventAdapter, PersistentStateAdapter}
import com.evernym.verity.config.ConfigConstants.{ENDORSER_REGISTRY_RETENTION_SNAPSHOT, SALT_EVENT_ENCRYPTION}
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.endorser_registry.EndorserRegistry.Commands.{AddEndorser, GetEndorsers, RemoveEndorser}
import com.evernym.verity.endorser_registry.States.{Endorser, ListOfEndorser}
import com.typesafe.config.Config


object EndorserRegistry {

  val TypeKey: EntityTypeKey[Cmd] = EntityTypeKey("EndorserRegistry")
  val DEFAULT_RETENTION_SNAPSHOT_AFTER_EVERY_EVENTS: Int = 100
  val DEFAULT_RETENTION_SNAPSHOT_KEEP_SNAPSHOTS: Int = 2
  val DEFAULT_RETENTION_SNAPSHOT_DELETE_EVENTS_ON_SNAPSHOTS: Boolean = false


  trait Event   //all events would be defined in endorser-registry-events.proto file
  trait State   //all states would be defined in endorser-registry-states.proto file (because of snapshotting purposes)


  trait Cmd extends ActorMessage
  object Commands {
    case class AddEndorser(ledger: String, did: DidStr, verKey: VerKeyStr, replyTo: ActorRef[Replies.EndorserAdded]) extends Cmd
    case class RemoveEndorser(ledger: String, did: DidStr, replyTo: ActorRef[Replies.EndorserRemoved]) extends Cmd
    case class GetEndorsers(ledger: String, replyTo: ActorRef[Replies.LedgerEndorsers]) extends Cmd
  }

  trait Reply extends ActorMessage
  object Replies {
    case class EndorserAdded(ledger: String, did: DidStr, verKey: VerKeyStr) extends Reply
    case class EndorserRemoved(ledger: String, did: DidStr) extends Reply
    case class LedgerEndorsers(endorsers: Seq[Endorser], latestEndorser: Option[Endorser]) extends Reply
  }

  def apply(entityId: String, configuration: Config): Behavior[Cmd] = {
    Behaviors.setup { _ =>
      val eventEncryptionSalt = prepareEventEncryptionSalt(configuration)
      val retentionCriteria = prepareRetentionCriteria(configuration)

      EventSourcedBehavior
        .withEnforcedReplies(
          PersistenceId(TypeKey.name, entityId),
          States.Initialized(),
          commandHandler,
          eventHandler)
        .receiveSignal(signalHandler())
        .eventAdapter(PersistentEventAdapter(entityId, EventObjectMapper, eventEncryptionSalt))
        .snapshotAdapter(PersistentStateAdapter(entityId, StateObjectMapper, eventEncryptionSalt))
        .withRetention(retentionCriteria)
    }
  }

  private def commandHandler: (State, Cmd) => ReplyEffect[Event, State] = {
    case (st: States.Initialized, cmd: AddEndorser)  =>
      val ledgerEndorsers = st.ledgerEndorsers.get(cmd.ledger).map(_.endorsers).getOrElse(Seq.empty)
      if (ledgerEndorsers.map(_.did).contains(cmd.did)) {
        Effect
          .reply(cmd.replyTo)(Replies.EndorserAdded(cmd.ledger, cmd.did, cmd.verKey))
      } else {
        Effect
          .persist(Events.EndorserAdded(cmd.ledger, cmd.did, cmd.verKey))
          .thenReply(cmd.replyTo)(_ => Replies.EndorserAdded(cmd.ledger, cmd.did, cmd.verKey))
      }

    case (st: States.Initialized, cmd: RemoveEndorser) =>
      val ledgerEndorsers = st.ledgerEndorsers.get(cmd.ledger).map(_.endorsers).getOrElse(Seq.empty)
      if (ledgerEndorsers.map(_.did).contains(cmd.did)) {
        Effect
          .persist(Events.EndorserRemoved(cmd.ledger, cmd.did))
          .thenReply(cmd.replyTo)(_ => Replies.EndorserRemoved(cmd.ledger, cmd.did))
      } else {
        Effect
          .reply(cmd.replyTo)(Replies.EndorserRemoved(cmd.ledger, cmd.did))
      }

    case (st: States.Initialized, cmd: GetEndorsers) =>
      val ledgerEndorsers = st.ledgerEndorsers.get(cmd.ledger).map(_.endorsers).getOrElse(Seq.empty)
      Effect
        .reply(cmd.replyTo)(Replies.LedgerEndorsers(ledgerEndorsers, ledgerEndorsers.lastOption))
  }

  private def eventHandler: (State, Event) => State = {
    case (st: States.Initialized, event: Events.EndorserAdded) =>
      val ledgerEndorsers = st.ledgerEndorsers.get(event.ledger).map(_.endorsers).getOrElse(Seq.empty)
      val updatedLedgerEndorsers = ledgerEndorsers :+ Endorser(event.did, event.verKey)
      st.copy(ledgerEndorsers = st.ledgerEndorsers ++ Map(event.ledger -> ListOfEndorser(updatedLedgerEndorsers)))

    case (st: States.Initialized, event: Events.EndorserRemoved) =>
      val ledgerEndorsers = st.ledgerEndorsers.get(event.ledger).map(_.endorsers).getOrElse(Seq.empty)
      val updatedLedgerEndorsers = ledgerEndorsers.filterNot(e => e.did == event.did)
      st.copy(ledgerEndorsers = st.ledgerEndorsers ++ Map(event.ledger -> ListOfEndorser(updatedLedgerEndorsers)))
  }

  private def signalHandler(): PartialFunction[(State, Signal), Unit] = {
    case (_: State, RecoveryCompleted) => //nothing
  }

  def prepareEventEncryptionSalt(config: Config): String = config.getString(SALT_EVENT_ENCRYPTION)

  def prepareRetentionCriteria(config: Config): RetentionCriteria = {
    RetentionCriteriaBuilder.build(
      config,
      ENDORSER_REGISTRY_RETENTION_SNAPSHOT,
      DEFAULT_RETENTION_SNAPSHOT_AFTER_EVERY_EVENTS,
      DEFAULT_RETENTION_SNAPSHOT_KEEP_SNAPSHOTS,
      DEFAULT_RETENTION_SNAPSHOT_DELETE_EVENTS_ON_SNAPSHOTS
    )
  }
}
