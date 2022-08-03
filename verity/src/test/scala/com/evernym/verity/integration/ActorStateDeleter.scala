package com.evernym.verity.integration

import akka.actor.Props
import akka.cluster.sharding.ShardRegion.EntityId
import akka.persistence.DeleteSnapshotSuccess
import com.evernym.verity.actor.base.Stop
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotterExt}
import com.evernym.verity.config.AppConfig

import scala.concurrent.ExecutionContext

class ActorStateDeleter(val futureExecutionContext: ExecutionContext,
                        val appConfig: AppConfig,
                        actorTypeName: String,
                        actorEntityId: EntityId,
                        persEncryptionKey: Option[String])
  extends BasePersistentActor
    with SnapshotterExt[Any] {

  override lazy val entityType: String = actorTypeName
  override lazy val entityId: String = actorEntityId
  override lazy val persistenceEncryptionKey: String = persEncryptionKey match {
    case Some(pk) => pk
    case None     => DefaultPersistenceEncryption.getEventEncryptionKey(entityId, appConfig)
  }

  override def receiveEvent: Receive = {
    case event => //nothing to do
  }

  override def receiveCmd: Receive = {
    case "string" => logger.info(s"[ActorStateDeleter: $persistenceId] doesn't handle command")
  }

  override def postRecoveryCompleted(): Unit = {
    super.postRecoveryCompleted()
    deleteAndStop()
  }

  def deleteAndStop(): Unit = {
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
    self ! Stop()
  }

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case state => //nothing to do
  }

  override def snapshotState: Option[Any] = None
}

object ActorStateDeleter {
  def props(futureExecutionContext: ExecutionContext,
            appConfig: AppConfig,
            actorTypeName: String,
            actorEntityId: EntityId,
            persEncryptionKey: Option[String]): Props =
    Props(new ActorStateDeleter(futureExecutionContext, appConfig, actorTypeName, actorEntityId, persEncryptionKey))
}