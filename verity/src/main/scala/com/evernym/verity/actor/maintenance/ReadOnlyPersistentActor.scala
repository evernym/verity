package com.evernym.verity.actor.maintenance

import akka.actor.Props
import com.evernym.verity.actor.persistence.{BasePersistentActor, DefaultPersistenceEncryption, SnapshotterExt}
import com.evernym.verity.actor.{ActorMessage, State}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.constants.Constants.YES

import scala.concurrent.ExecutionContext
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

class ReadOnlyPersistentActor(val appConfig: AppConfig, actorParam: ActorParam, executionContext: ExecutionContext)
  extends BasePersistentActor
    with SnapshotterExt[State]
    with DefaultPersistenceEncryption {

  override def futureExecutionContext: ExecutionContext = executionContext

  override def receiveCmd: Receive = {
    case SendSummary =>
      sender ! SummaryData(data.exists(_.isSnapshot), data.count(!_.isSnapshot))
    case SendAggregated =>
      sender ! AggregatedData(data.groupBy(_.message.getClass.getSimpleName).mapValues(_.size))
    case sa: SendAll =>
      val resp = {
        if (sa.withData) AllData(data)
        else AllData(data.map(d => d.copy(message = d.message.getClass.getSimpleName)))
      }
      sender ! resp
  }

  override def receiveEvent: Receive = {
    case evt => data = data :+ PersistentData(lastSequenceNr, evt)
  }

  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case state => data = data :+ PersistentData(lastSequenceNr, state, isSnapshot = true)
  }

  var data: List[PersistentData] = List.empty

  //persistence id is calculated based on 'entityType' and 'entityId'
  override lazy val entityType: String = actorParam.actorTypeName

  //if target actor is using 'DefaultPersistenceEncryption'
  // then this actor specifically needs to know 'entityId' which is used
  // in calculating symmetric encryption key
  override lazy val entityId: String = actorParam.actorEntityId
  override def recoverFromSnapshot: Boolean = actorParam.recoverFromSnapshot

  override def getEventEncryptionKey: String =
    actorParam.persEncKey match {
      case Some(pk) => appConfig.getStringOption(pk).getOrElse(pk)
      case None     => super.getEventEncryptionKey
    }



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

  var _lastEventRecoverLoggedAtSeqNr: Long = -1L
  override def postEventHandlerApplied(): Unit = {
    super.postEventHandlerApplied()
    if (lastSequenceNr == 1 || lastSequenceNr == (_lastEventRecoverLoggedAtSeqNr * 10)) {
      logger.info(s"[ROP: $actorId]: postEventHandlerApplied (lastSequenceNr: $lastSequenceNr)")
      _lastEventRecoverLoggedAtSeqNr = lastSequenceNr
    }
  }

  override def postRecoveryCompleted(): Unit = {
    logger.info(s"[ROP: $actorId]: postRecoveryCompleted (lastSequenceNr: $lastSequenceNr)")
    super.postRecoveryCompleted()
  }

  override def postSuccessfulActorRecovery(): Unit = {
    logger.info(s"[ROP: $actorId]: postSuccessfulActorRecovery (lastSequenceNr: $lastSequenceNr)")
    super.postSuccessfulActorRecovery()
    context.setReceiveTimeout(5.minutes)
  }
}

case object SendSummary extends ActorMessage
case object SendAggregated extends ActorMessage
object SendAll {
  def apply(withData: String): SendAll = SendAll(withData == YES)
}
case class SendAll(withData: Boolean) extends ActorMessage

/**
 *
 * @param lastSeqNo last sequence number
 * @param message recovered event or snapshot
 */
case class PersistentData(lastSeqNo: Long, message: Any, isSnapshot: Boolean = false) {
  override def toString: String = s"$lastSeqNo: $message"
}

trait PersistentDataResp extends ActorMessage {
  def toRecords: List[String]
}
case class AllData(data: List[PersistentData]) extends PersistentDataResp {
  override def toRecords: List[String] = data.map(_.toString)
}
case class AggregatedData(data: Map[String, Int]) extends PersistentDataResp {
  override def toRecords: List[String] = data.map(r => s"${r._1} -> ${r._2}").toList
}
case class SummaryData(recoveredSnapshot: Boolean, recoveredEvents: Int) extends ActorMessage

object ReadOnlyPersistentActor {
  def prop(appConfig: AppConfig, actorParam: ActorParam, executionContext: ExecutionContext): Props =
    Props(new ReadOnlyPersistentActor(appConfig, actorParam, executionContext))
}

case class ActorParam(actorTypeName: String,
                      actorEntityId: String,
                      recoverFromSnapshot: Boolean = true,
                      persEncKey: Option[String]=None) {
  def id: String = actorTypeName + actorEntityId
}