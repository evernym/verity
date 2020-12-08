package com.evernym.verity.actor.maintenance

import akka.actor.Props
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotterExt}
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, State}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil
import com.typesafe.scalalogging.Logger

/**
 * This actor can be used as sharded or non sharded persistent actor for read purposes only.
 * If used as non sharded, then based on where (actor context) this actor gets created,
 *  there can be multiple instances of same persistent actor ('actorTypeName' and 'actorEntityId')
 *  on different nodes or at different path in same actor system.
 *
 * This actor can be used to read/retrieve all snapshots/events of any persistent actor
 * for read, analyze or other similar purposes (like migrating those events to a new persistent actor etc)
 *
 * @param appConfig application config
 * @param actorTypeName for sharded actor it would be shard region name
 * @param actorEntityId entity id of the actor
 */

//TODO: As of now, this actor is extending from 'DefaultPersistenceEncryption'
// so it will be able to read only those actors which extends/uses 'DefaultPersistenceEncryption'
// for other actors we'll have to tweak this actor to also take 'persistenceEncryptionKey'
// as it's construction parameter and override it.

class ReadOnlyPersistentActor(val appConfig: AppConfig, actorTypeName: String, actorEntityId: String)
  extends BasePersistentActor
    with SnapshotterExt[State]
    with DefaultPersistenceEncryption {

  override def receiveCmd: Receive = {
    case SendPersistedData => sender ! PersistentData(snapshot, events)
    case cmd => logger.warn("read only persistent actor does not support command: " + cmd)
  }

  override def receiveEvent: Receive = {
    case evt => events = events :+ evt
  }

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case state => snapshot = Option(state)
  }

  var snapshot: Option[Any] = None
  var events: List[Any] = List.empty

  override lazy val entityName: String = actorTypeName
  override lazy val entityId: String = actorEntityId

  val logger: Logger = LoggingUtil.getLoggerByClass(classOf[ReadOnlyPersistentActor])

  //We don't want this read only actor to write/persist any state/event
  // hence override these functions to throw exception if at all accidentally used by this actor
  override def writeAndApply(evt: Any): Unit = ???
  override def writeWithoutApply(evt: Any): Unit = ???
  override def asyncWriteAndApply(evt: Any): Unit = ???
  override def asyncWriteWithoutApply(evt: Any): Unit = ???
  override def snapshotState: Option[State] = ???
}

case object SendPersistedData extends ActorMessageObject

case class PersistentData(snapshotStat: Option[Any], events: List[Any]) extends ActorMessageClass

object ReadOnlyPersistentActor {
  def prop(appConfig: AppConfig, actorTypeName: String, actorEntityId: String): Props =
    Props(new ReadOnlyPersistentActor(appConfig, actorTypeName, actorEntityId))
}