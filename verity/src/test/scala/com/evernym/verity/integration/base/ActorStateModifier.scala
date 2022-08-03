package com.evernym.verity.integration.base

import akka.actor.Props
import akka.cluster.sharding.ShardRegion.EntityId
import akka.persistence.DeleteSnapshotSuccess
import com.evernym.verity.actor.base.Stop
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotterExt}
import com.evernym.verity.config.AppConfig

import scala.concurrent.ExecutionContext

//either modifies/delete events as per given `eventModifier` function or delete all events/snapshot
class ActorStateModifier(val futureExecutionContext: ExecutionContext,
                         val appConfig: AppConfig,
                         actorTypeName: String,
                         actorEntityId: EntityId,
                         persEncryptionKey: Option[String],
                         eventModifier: PartialFunction[Any, Option[Any]] = PartialFunction.empty)
  extends BasePersistentActor
    with SnapshotterExt[Any] {

  override lazy val entityType: String = actorTypeName
  override lazy val entityId: String = actorEntityId
  override lazy val persistenceEncryptionKey: String = persEncryptionKey match {
    case Some(pk) => pk
    case None     => DefaultPersistenceEncryption.getEventEncryptionKey(entityId, appConfig)
  }

  override def receiveEvent: Receive = {
    case event =>
      recoveredEvents = recoveredEvents:+ event
      if (modifiedEventsToBeRepersisted.nonEmpty && modifiedEventsToBeRepersisted.size == recoveredEvents.size) {
        self ! Stop()
      }
  }

  override def receiveCmd: Receive = {
    case "string" => logger.info(s"[ActorStateDeleter: $persistenceId] doesn't handle command")
  }

  override def postRecoveryCompleted(): Unit = {
    super.postRecoveryCompleted()
    recoveredEvents.foreach { event =>
      if (eventModifier.isDefinedAt(event)) {
        eventModifier.apply(event).foreach { event =>
          modifiedEventsToBeRepersisted = modifiedEventsToBeRepersisted :+ event
        }
      }
    }
    recoveredEvents = Seq.empty
    modifyStateAndStop()
  }

  def modifyStateAndStop(): Unit = {
    logger.info(s"[ActorStateDeleter: $persistenceId] about to delete events till lastSequenceNr: $lastSequenceNr, snapshotSequenceNr: $snapshotSequenceNr")
    deleteMessagesExtended(lastSequenceNr)
  }

  //this gets called when all the events are deleted
  override def postAllMsgsDeleted(): Unit = {
    logger.info(s"[ActorStateDeleter: $persistenceId] all events deleted")
    deleteSnapshot(snapshotSequenceNr)
  }

  //this gets called when all the snapshot gets deleted
  override def postDeleteSnapshotSuccess(dss: DeleteSnapshotSuccess): Unit = {
    logger.info(s"[ActorStateDeleter: $persistenceId] snapshot deleted: " + dss)
    if (modifiedEventsToBeRepersisted.isEmpty) self ! Stop()
    else writeAndApplyAll(modifiedEventsToBeRepersisted.toList)
  }

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case state => //nothing to do
  }

  override def snapshotState: Option[Any] = None

  var recoveredEvents: Seq[Any] = Seq.empty
  var modifiedEventsToBeRepersisted: Seq[Any] = Seq.empty
}

object ActorStateModifier {
  def props(futureExecutionContext: ExecutionContext,
            appConfig: AppConfig,
            actorTypeName: String,
            actorEntityId: EntityId,
            persEncryptionKey: Option[String],
            eventModifier: PartialFunction[Any, Option[Any]] = PartialFunction.empty): Props =
    Props(new ActorStateModifier(futureExecutionContext, appConfig, actorTypeName, actorEntityId, persEncryptionKey, eventModifier))
}