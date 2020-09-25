package com.evernym.verity.actor.persistence

import akka.persistence.{DeleteSnapshotsFailure, DeleteSnapshotsSuccess, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer, SnapshotSelectionCriteria, Snapshotter}
import com.evernym.verity.actor.event.serializer.{DefaultStateSerializer, StateSerializer}
import com.evernym.verity.constants.LogKeyConstants.{LOG_KEY_ERR_MSG, LOG_KEY_PERSISTENCE_ID}
import com.evernym.verity.actor.{State, TransformedState}
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_DYNAMODB_SNAPSHOT_FAILED_COUNT, AS_SERVICE_DYNAMODB_SNAPSHOT_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.util.Util.logger

/**
 * persistent actor can extend from this trait and provide required
 * implementation to configure it's snapshot related persistence behaviour
 */
trait SnapshotterExt extends Snapshotter { this :BasePersistentActor =>

  val stateSerializer: StateSerializer = DefaultStateSerializer

  /**
   * configuration object to decide persistence behaviour (like shall it use snapshots etc)
   * @return
   */
  def persistenceConfig: PersistenceConfig

  /**
   * should be overridden by implementing class and return optional state which should be stored in snapshot,
   * only used if 'persistenceConfig.autoSnapshotAfterEvents' is configured as true.
   * @return
   */
  def getStateForSnapshot: Option[State]

  /**
   * default snapshot callback command handlers
   * can be overridden by implementing class if any change is required
   * @return
   */
  def snapshotCallbackCommandHandler: Receive = {

    case SaveSnapshotSuccess(metadata) =>
      logger.debug("snapshot saved successfully", (LOG_KEY_PERSISTENCE_ID, persistenceId),
        ("metadata", metadata))
      ActorMetrics.incrementGauge(AS_SERVICE_DYNAMODB_SNAPSHOT_SUCCEED_COUNT)
      if (persistenceConfig.deleteSnapshotsOlderThanRecentSnapshot && metadata.sequenceNr > 0)
        deleteSnapshots(SnapshotSelectionCriteria(metadata.sequenceNr - 1))
      if (persistenceConfig.deleteEventsOlderThanRecentSnapshot)
        deleteMessages(metadata.sequenceNr)

    case SaveSnapshotFailure(metadata, reason) =>
      logger.error("could not save snapshot", (LOG_KEY_PERSISTENCE_ID, persistenceId),
        ("metadata", metadata), (LOG_KEY_ERR_MSG, reason))
      MetricsWriter.gaugeApi.increment(AS_SERVICE_DYNAMODB_SNAPSHOT_FAILED_COUNT)

    case dss: DeleteSnapshotsSuccess =>
      logger.debug("old snapshots deleted successfully", (LOG_KEY_PERSISTENCE_ID, persistenceId),
        ("selection_criteria", dss.criteria))

    case dsf: DeleteSnapshotsFailure =>
      logger.error("could not delete old snapshots", (LOG_KEY_PERSISTENCE_ID, persistenceId),
        ("selection_criteria", dsf.criteria), (LOG_KEY_ERR_MSG, dsf.cause))
  }

  def defaultSnapshotOfferReceiver: Receive = {
    case so: SnapshotOffer =>
      val ts = so.snapshot.asInstanceOf[TransformedState]
      val dse = stateSerializer.getDeSerializedState(ts, persistenceEncryptionKey)
      receiveEvent(dse)
  }

  private def buildTransformedSnapshot(state: State): TransformedState = {
    stateSerializer.getTransformedState(state, persistenceEncryptionKey)
  }

  def saveSnapshotIfNeeded(): Unit = {
    persistenceConfig.autoSnapshotAfterEvents match {
      case Some(v) if v > 0 && lastSequenceNr % v == 0 =>
        getStateForSnapshot.foreach(saveSnapshot)
      case _ => None
    }
  }

  final override def postPersist(): Unit = saveSnapshotIfNeeded()

  final override def postPersistAsync(): Unit = saveSnapshotIfNeeded()

  final override def receiveRecover: Receive = defaultSnapshotOfferReceiver orElse handleEvent

  final override def receiveCommand: Receive = handleCommand(cmdHandler) orElse snapshotCallbackCommandHandler orElse receiveCmdBase

  final override def saveSnapshot(snapshot: Any): Unit = {
    snapshot match {
      case s: State => super.saveSnapshot(buildTransformedSnapshot(s))
      case x => throw new RuntimeException(s"'${x.getClass.getName}' is not a 'State'")
    }
  }

}

