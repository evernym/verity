package com.evernym.verity.actor.maintenance.v1tov2migration

import akka.actor.Props
import akka.cluster.sharding.ShardRegion.EntityId
import akka.persistence.RecoveryCompleted
import com.evernym.verity.actor.{PersistentMsg, PersistentMultiEventMsg}
import com.evernym.verity.actor.base.Stop
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.MultiEvent

import scala.concurrent.ExecutionContext


class EventPersister(val futureExecutionContext: ExecutionContext,
                     val appConfig: AppConfig,
                     actorTypeName: String,
                     actorEntityId: EntityId,
                     persEncryptionKey: Option[String],
                     events: Seq[Any])

  extends BasePersistentActor {

  override lazy val entityType: String = actorTypeName
  override lazy val entityId: String = actorEntityId
  override lazy val persistenceEncryptionKey: String = persEncryptionKey match {
    case Some(pk) => pk
    case None     => DefaultPersistenceEncryption.getEventEncryptionKey(entityId, appConfig)
  }

  override def handleEvent: Receive = {
    case RecoveryCompleted              => handleRecoveryCompleted()
    case pm: PersistentMsg              => recoveredEvents = recoveredEvents ++ untransformedEvents(Seq(pm))
    case pmem: PersistentMultiEventMsg  => recoveredEvents = recoveredEvents :+ MultiEvent(untransformedEvents(pmem.events))
  }

  private def untransformedEvents(transformedEvents: Seq[Any]): Seq[Any] = {
    transformedEvents.map {
      case pm: PersistentMsg => lookupTransformer(pm.transformationId).undo(pm)
    }
  }

  override def receiveEvent: Receive = {
    case event => recoveredEvents = recoveredEvents:+ event
  }

  override def receiveCmd: Receive = {
    case cmd => logger.info(s"[EventPersister: $persistenceId] doesn't handle command: " + cmd)
  }

  override def postRecoveryCompleted(): Unit = {
    super.postRecoveryCompleted()
    persistAndStop()
  }

  def persistAndStop(): Unit = {
    val unpersistedEvents = events.diff(recoveredEvents)
    if (recoveredEvents.nonEmpty) {
      val recoveredEventNames = recoveredEvents.map(_.getClass.getSimpleName)
      logger.info(s"[EventPersister: $persistenceId] found already persisted events: " + recoveredEventNames.mkString(", "))
      val newEventNames = unpersistedEvents.map(_.getClass.getSimpleName).mkString(", ")
      logger.info(s"[EventPersister: $persistenceId] new events to be persisted: " + newEventNames)
    }
    unpersistedEvents.foreach { evt =>
      writeWithoutApply(evt)
    }
    self ! Stop()
  }

  var recoveredEvents: Seq[Any] = Seq.empty
}

object EventPersister {
  def props(executionContext: ExecutionContext,
            appConfig: AppConfig,
            actorTypeName: String,
            actorEntityId: EntityId,
            persEncryptionKey: Option[String],
            events: Seq[Any]): Props =
    Props(new EventPersister(executionContext, appConfig, actorTypeName, actorEntityId, persEncryptionKey, events))
}