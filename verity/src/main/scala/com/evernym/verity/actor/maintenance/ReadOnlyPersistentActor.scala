package com.evernym.verity.actor.maintenance

import akka.actor.Props
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotterExt}
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, State}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil
import com.typesafe.scalalogging.Logger

/**
 * This actor can be used as sharded or non sharded actor for read purposes only.
 * If used as non sharded, then based on where (actor context) this actor gets created,
 *  there can be multiple instances of same persistent actor ('actorTypeName' and 'actorEntityId')
 *  on different nodes or at different path in same actor system.
 *
 * This actor can be used to read/retrieve all snapshots/events of any persistent actor
 * for read, analyze or other similar purposes (like migrating those events to a new persistent actor etc)
 *
 * Once this actor is started, it won't read any new events added to the given persistence id
 * by its original actor until this actor gets restarted.
 *
 * @param appConfig application config
 * @param actorParam actor param
 */

class ReadOnlyPersistentActor(val appConfig: AppConfig, actorParam: ActorParam)
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

  //persistence id is calculated based on 'entityName' and 'entityId'
  override lazy val entityName: String = actorParam.actorTypeName

  //if target actor is using 'DefaultPersistenceEncryption'
  // then this actor specifically needs to know 'entityId' which is used
  // in calculating symmetric encryption key
  override lazy val entityId: String = actorParam.actorEntityId

  override def getEventEncryptionKeyWithoutWallet: String =
    actorParam.persEncKeyConfPath.map(appConfig.getConfigStringReq)
      .getOrElse(super.getEventEncryptionKeyWithoutWallet)

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
  def prop(appConfig: AppConfig, actorParam: ActorParam): Props =
    Props(new ReadOnlyPersistentActor(appConfig, actorParam))
}

case class ActorParam(actorTypeName: String, actorEntityId: String, persEncKeyConfPath: Option[String]=None) {
  def id: String = actorTypeName + actorEntityId
}