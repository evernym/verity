package com.evernym.verity.actor.maintenance

import akka.actor.Props
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotterExt}
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, State}
import com.evernym.verity.config.AppConfig

import scala.concurrent.duration._

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
    case SendPersistedData => sender ! PersistentDataWrapper(data)
  }

  override def receiveEvent: Receive = {
    case evt => data = data :+ PersistentData(lastSequenceNr, evt)
  }

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case state => data = data :+ PersistentData(lastSequenceNr, state)
  }

  var data: List[PersistentData] = List.empty

  //persistence id is calculated based on 'entityName' and 'entityId'
  override lazy val entityName: String = actorParam.actorTypeName

  //if target actor is using 'DefaultPersistenceEncryption'
  // then this actor specifically needs to know 'entityId' which is used
  // in calculating symmetric encryption key
  override lazy val entityId: String = actorParam.actorEntityId
  override def recoverFromSnapshot: Boolean = actorParam.recoverFromSnapshot

  override def getEventEncryptionKeyWithoutWallet: String =
    actorParam.persEncKeyConfPath.map(appConfig.getConfigStringReq)
      .getOrElse(super.getEventEncryptionKeyWithoutWallet)

  //We don't want this read only actor to write/persist any state/event
  // hence override these functions to throw exception if at all accidentally used by this actor
  override def writeAndApply(evt: Any): Unit =
    throw new UnsupportedOperationException("read only actor doesn't support persistence")
  override def writeWithoutApply(evt: Any): Unit =
    throw new UnsupportedOperationException("read only actor doesn't support persistence")
  override def asyncWriteAndApply(evt: Any): Unit =
    throw new UnsupportedOperationException("read only actor doesn't support persistence")
  override def asyncWriteWithoutApply(evt: Any): Unit =
    throw new UnsupportedOperationException("read only actor doesn't support persistence")
  override def snapshotState: Option[State] = None

  context.setReceiveTimeout(5.minutes)
}

case object SendPersistedData extends ActorMessageObject

case class PersistentData(lastSeqNo: Long, event: Any) {
  override def toString: String = s"$lastSeqNo: $event"
}
case class PersistentDataWrapper(data: List[PersistentData]) extends ActorMessageClass

object ReadOnlyPersistentActor {
  def prop(appConfig: AppConfig, actorParam: ActorParam): Props =
    Props(new ReadOnlyPersistentActor(appConfig, actorParam))
}

case class ActorParam(actorTypeName: String,
                      actorEntityId: String,
                      recoverFromSnapshot: Boolean = true,
                      persEncKeyConfPath: Option[String]=None) {
  def id: String = actorTypeName + actorEntityId
}