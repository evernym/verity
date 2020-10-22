package com.evernym.verity.actor.persistence

import akka.persistence.{DeleteSnapshotsFailure, DeleteSnapshotsSuccess, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer, SnapshotSelectionCriteria, Snapshotter}
import com.evernym.verity
import com.evernym.verity.constants.LogKeyConstants.{LOG_KEY_ERR_MSG, LOG_KEY_PERSISTENCE_ID}
import com.evernym.verity.actor.{PersistentData, State, TransformedState}
import com.evernym.verity.metrics.CustomMetrics.{AS_SERVICE_DYNAMODB_SNAPSHOT_FAILED_COUNT, AS_SERVICE_DYNAMODB_SNAPSHOT_SUCCEED_COUNT}
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.transformations.transformers.<=>
import com.evernym.verity.util.Util.logger


/**
 * persistent actor can extend from this trait and provide required
 * implementation to configure it's snapshot related persistence behaviour
 */
trait SnapshotterExt[S <: verity.actor.State] extends Snapshotter { this: BasePersistentActor =>

  /**
   * a snapshot handler (used during actor recovery)
   * @return
   */
  def receiveSnapshot: PartialFunction[Any, Unit]

  /**
   * should be overridden by implementing class and return optional
   * state which should be stored as snapshot, this is only used
   * if 'persistenceConfig.snapshotEveryNEvents' is configured as non empty and greater than zero
   * or explicitly trying to save snapshot (by calling 'saveSnapshotStateIfAvailable' method)
   *
   * @return
   */
  def snapshotState: Option[S]

  /**
   * reads configuration value based on entity's type/name
   */
  lazy val snapshotAfterNEvents: Option[Int] =
    PersistentActorConfigUtil.getSnapshotAfterNEvents(appConfig,
      normalizedEntityCategoryName, normalizedEntityName, normalizedEntityId)

  lazy val keepNSnapshots: Option[Int] =
    PersistentActorConfigUtil.getKeepNSnapshots(appConfig,
      normalizedEntityCategoryName, normalizedEntityName, normalizedEntityId)

  lazy val deleteEventsOnSnapshots: Option[Boolean] =
    PersistentActorConfigUtil.getDeleteEventsOnSnapshots(appConfig,
      normalizedEntityCategoryName, normalizedEntityName, normalizedEntityId)

  /**
   * configuration object to decide persistence behaviour (like shall it use snapshots etc)
   * @return
   */
  lazy val persistenceConfig: PersistenceConfig = {
    if (snapshotAfterNEvents.exists(_ > 0) && keepNSnapshots.contains(0)) {
      throw new RuntimeException("keepNSnapshots should be greater than 0 (if snapshotAfterNEvents is greater than 0)")
    }
    PersistenceConfig (
      allowOnlyEvents = false,
      allowOnlySnapshots = false,
      snapshotEveryNEvents = snapshotAfterNEvents orElse None,            //by default snapshot is NOT enabled
      keepNSnapshots = keepNSnapshots orElse Option(1),                   //if snapshot enabled, by default it will keep 1 snapshot
      deleteEventsOnSnapshot = deleteEventsOnSnapshots.getOrElse(false)   //if snapshot enabled, by default it will delete events on snapshot
    )
  }

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
      (persistenceConfig.keepNSnapshots, persistenceConfig.snapshotEveryNEvents).zipped.foreach {
        (keepNSnapshot, snapshotEveryNEvents) => {
          val maxSequenceNr = metadata.sequenceNr - (keepNSnapshot * snapshotEveryNEvents)
          if (maxSequenceNr > 0)
            deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr))
        }
      }
      if (persistenceConfig.deleteEventsOnSnapshot)
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

  /**
   * transforms given generic proto buf wrapper message (TransformedState, PersistentData)
   * by 'transformer' to a plain (deserialized, decrypted) state object
   * which actor can use to replace it's state.
   *
   * instead of hardcoding transformer, we lookup appropriate transformer based on
   * 'transformationId' available in serialized state to make sure it is backward compatible.
   *
   * @return
   */
  def defaultSnapshotOfferReceiver: Receive = {
    case so: SnapshotOffer =>
      val state = so.snapshot match {
        case ts: TransformedState =>    //legacy persisted state
          lookupTransformer(ts.transformationId, Option(LEGACY_PERSISTENT_OBJECT_TYPE_STATE)).undo(ts)
        case pd: PersistentData =>      //for newly persisted state
          lookupTransformer(pd.transformationId).undo(pd)
        case x => throw new RuntimeException("snapshot type not supported: " + x.getClass)
      }
      receiveSnapshot(state)
  }

  /**
   * gets called after any event gets persisted by this actor
   * to determine if a snapshot needs to be persisted or not
   *
   */
  def saveSnapshotIfNeeded(): Unit = {
    persistenceConfig.snapshotEveryNEvents match {
      case Some(v) if v > 0 && (lastSequenceNr % v) == 0  => saveSnapshotStateIfAvailable()
      case _                                              => None
    }
  }

  /**
   * reason for overriding is that we wanted to make sure that
   * snapshots gets encrypted before it goes to the persistence layer.
   *
   * @param snapshot state to be snapshotted
   */
  final override def saveSnapshot(snapshot: Any): Unit = {
    transformAndSaveSnapshot(snapshot)
  }

  /**
   * this is to be called manually/explicitly by implementing class
   */
  final def saveSnapshotStateIfAvailable(): Unit = {
    snapshotState.foreach(transformAndSaveSnapshot)
  }

  //TODO: Below we are using 'legacyStateTransformer'.
  // When we should change it to 'persistenceTransformer' (more optimized one)?
  // It can be changed anytime with backward compatibility as during 'undo' operation
  // we do lookup appropriate transformer based on 'transformationId'.

  /**
   * transformer used for state persistence
   */
  lazy val stateTransformer: Any <=> TransformedState = legacyStateTransformer

  /**
   * serialize, encrypt and then save the given snapshot
   *
   * @param state state to be snapshotted
   */
  def transformAndSaveSnapshot(state: Any): Unit = {
    state match {
      case s: State =>
        val ts = stateTransformer.execute(s)
        PersistenceSerializerValidator.validate(ts, appConfig)
        super.saveSnapshot(ts)
      case other    => throw new RuntimeException(s"'${other.getClass.getName}' is not a 'State'")
    }
  }

  final override def postPersist(): Unit = saveSnapshotIfNeeded()

  final override def postPersistAsync(): Unit = saveSnapshotIfNeeded()

  final override def receiveRecover: Receive = defaultSnapshotOfferReceiver orElse handleEvent

  final override def receiveCommand: Receive = handleCommand(cmdHandler) orElse snapshotCallbackCommandHandler orElse receiveCmdBase

}
