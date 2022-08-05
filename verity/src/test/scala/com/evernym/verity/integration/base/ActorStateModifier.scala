package com.evernym.verity.integration.base

import akka.actor.Props
import akka.cluster.sharding.ShardRegion.EntityId
import akka.persistence.DeleteSnapshotSuccess
import com.evernym.verity.actor.base.Stop
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotterExt}
import com.evernym.verity.config.AppConfig

import scala.concurrent.ExecutionContext

//modifies and/or deletes the state based on given `eventMapper`
// if `eventMapper` is empty, it will delete the state completely
// if `eventMapper` is not empty:
//      * it will prepare new events to be persisted based on what `eventMapper` returns for already persisted events
//      * then it will delete the state completely and re-persist the new events it prepared in previous step
class ActorStateModifier(val futureExecutionContext: ExecutionContext,
                         val appConfig: AppConfig,
                         actorTypeName: String,
                         actorEntityId: EntityId,
                         persEncryptionKey: Option[String],
                         eventMapper: PartialFunction[Any, Option[Any]] = PartialFunction.empty)
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
      if (mappedEvents.nonEmpty && mappedEvents.size == recoveredEvents.size) {
        self ! Stop()
      }
  }

  override def receiveCmd: Receive = {
    case "string" => logger.info(s"[ActorStateModifier: $persistenceId] doesn't handle command")
  }

  override def postRecoveryCompleted(): Unit = {
    super.postRecoveryCompleted()
    recoveredEvents.foreach { event =>
      if (eventMapper.isDefinedAt(event)) {
        eventMapper.apply(event).foreach { event =>
          mappedEvents = mappedEvents :+ event
        }
      }
    }
    recoveredEvents = Seq.empty
    modifyStateAndStop()
  }

  def modifyStateAndStop(): Unit = {
    logger.info(s"[ActorStateModifier: $persistenceId] about to delete events till lastSequenceNr: $lastSequenceNr, " +
      s"snapshotSequenceNr: $snapshotSequenceNr")
    deleteMessagesExtended(lastSequenceNr)
  }

  //this gets called when all the events are deleted
  override def postAllMsgsDeleted(): Unit = {
    logger.info(s"[ActorStateModifier: $persistenceId] all events deleted")
    deleteSnapshot(snapshotSequenceNr)
  }

  //this gets called when the snapshot deletion is successful
  override def postDeleteSnapshotSuccess(dss: DeleteSnapshotSuccess): Unit = {
    logger.info(s"[ActorStateModifier: $persistenceId] snapshot deleted: " + dss)
    if (mappedEvents.isEmpty) self ! Stop()
    else writeAndApplyAll(mappedEvents.toList)
  }

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case state => //nothing to do
  }

  override def snapshotState: Option[Any] = None

  var recoveredEvents: Seq[Any] = Seq.empty

  //the new events to be persisted after existing event/snapshot cleanup
  var mappedEvents: Seq[Any] = Seq.empty
}

object ActorStateModifier {
  def props(futureExecutionContext: ExecutionContext,
            appConfig: AppConfig,
            actorTypeName: String,
            actorEntityId: EntityId,
            persEncryptionKey: Option[String],
            eventMapper: PartialFunction[Any, Option[Any]] = PartialFunction.empty): Props =
    Props(new ActorStateModifier(futureExecutionContext, appConfig, actorTypeName, actorEntityId, persEncryptionKey, eventMapper))
}