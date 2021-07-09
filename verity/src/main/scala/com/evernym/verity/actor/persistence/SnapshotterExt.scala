package com.evernym.verity.actor.persistence

import akka.persistence._
import com.evernym.verity.actor.{DeprecatedStateMsg, PersistentMsg, State}
import com.evernym.verity.config.CommonConfig.PERSISTENCE_SNAPSHOT_MAX_ITEM_SIZE_IN_BYTES
import com.evernym.verity.constants.LogKeyConstants.{LOG_KEY_ERR_MSG, LOG_KEY_PERSISTENCE_ID}
import com.evernym.verity.logging.ThrottledLogger
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.transformations.transformers.<=>

import scala.concurrent.duration._


/**
 * persistent actor can extend from this trait and provide required
 * implementation to configure it's snapshot related persistence behaviour
 */
trait SnapshotterExt[S] extends Snapshotter { this: BasePersistentActor =>

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
      normalizedEntityCategoryName, normalizedEntityType, normalizedEntityId)

  lazy val keepNSnapshots: Option[Int] =
    PersistentActorConfigUtil.getKeepNSnapshots(appConfig,
      normalizedEntityCategoryName, normalizedEntityType, normalizedEntityId)

  lazy val deleteEventsOnSnapshots: Option[Boolean] =
    PersistentActorConfigUtil.getDeleteEventsOnSnapshots(appConfig,
      normalizedEntityCategoryName, normalizedEntityType, normalizedEntityId)

  /**
   * configuration object to decide persistence behaviour (like shall it use snapshots etc)
   * @return
   */
  lazy val snapshotConfig: SnapshotConfig = {
    SnapshotConfig (
      snapshotEveryNEvents = snapshotAfterNEvents orElse None,            //by default snapshot is NOT enabled
      keepNSnapshots = keepNSnapshots orElse Option(1),                   //if snapshot enabled, by default it will keep 1 snapshot
      deleteEventsOnSnapshot = deleteEventsOnSnapshots.getOrElse(false)   //if snapshot enabled, by default it won't delete events on snapshot
    )
  }

  /**
   * default snapshot callback command handlers
   * can be overridden by implementing class if any change is required
   * @return
   */
  private def snapshotCallbackHandler: Receive = {

    case SaveSnapshotSuccess(metadata) =>
      metricsWriter.get().gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_SUCCEED_COUNT)
      snapshotCount = snapshotCount + 1
      logger.info(s"[$persistenceId] snapshot saved successfully, " +
        s"snapshot taken count (in current session): " + snapshotCount)
      if (keepNSnapshots.exists(snapshotCount > _)) {
        snapshotConfig.getDeleteSnapshotCriteria(metadata.sequenceNr).foreach { ssc =>
          metricsWriter.get().gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_DELETE_ATTEMPT_COUNT)
          deleteSnapshots(ssc)
        }
      }
      if (snapshotConfig.deleteEventsOnSnapshot) {
        deleteMessages(metadata.sequenceNr)
      }

    case SaveSnapshotFailure(metadata, reason) =>
      metricsWriter.get().gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_FAILED_COUNT)
      logger.warn(s"[$persistenceId] could not save snapshot (sequenceNr: ${metadata.sequenceNr}): $reason")

    case dss: DeleteSnapshotsSuccess =>
      metricsWriter.get().gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_DELETE_SUCCEED_COUNT)
      logger.debug("old snapshots deleted successfully", (LOG_KEY_PERSISTENCE_ID, persistenceId),
        ("selection_criteria", dss.criteria))

    case dsf: DeleteSnapshotsFailure =>
      metricsWriter.get().gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_DELETE_FAILED_COUNT)
      logger.info("could not delete old snapshots", (LOG_KEY_PERSISTENCE_ID, persistenceId),
        ("selection_criteria", dsf.criteria), (LOG_KEY_ERR_MSG, dsf.cause))

    case dss: DeleteSnapshotSuccess =>
      metricsWriter.get().gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_DELETE_SUCCEED_COUNT)
      logger.debug("old snapshot deleted successfully", (LOG_KEY_PERSISTENCE_ID, persistenceId),
        ("sequenceNr", dss.metadata.sequenceNr))

    case dsf: DeleteSnapshotFailure =>
      metricsWriter.get().gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_DELETE_FAILED_COUNT)
      logger.info("could not delete old snapshot", (LOG_KEY_PERSISTENCE_ID, persistenceId),
        ("sequenceNr", dsf.metadata.sequenceNr), (LOG_KEY_ERR_MSG, dsf.cause))
  }

  /**
   * transforms given generic proto buf wrapper message (DeprecatedStateMsg, PersistentMsg)
   * by 'transformer' to a plain (decrypted and deserialized) state object
   * which actor can use to recover it's state.
   *
   * instead of hardcoding transformer, we lookup appropriate transformer based on
   * 'transformationId' available in serialized state to make sure it is backward compatible.
   *
   * @return
   */
  def defaultSnapshotOfferReceiver: Receive = {
    case so: SnapshotOffer =>
      isAnySnapshotOffered = true
      snapshotCount = snapshotCount + 1
      val state = so.snapshot match {
        case dsm: DeprecatedStateMsg =>     //legacy persisted state
          lookupTransformer(dsm.transformationId, Option(LEGACY_PERSISTENT_OBJECT_TYPE_STATE)).undo(dsm)
        case pm: PersistentMsg =>           //for newly persisted state
          lookupTransformer(pm.transformationId).undo(pm)
        case x => throw new RuntimeException("snapshot state type not supported: " + x.getClass)
      }
      receiveSnapshot(state)
  }

  /**
   * gets called after event handler is called (post actor recovery only [not during actor recovery])
   * to determine if a snapshot needs to be persisted or not
   *
   */
  override def executeOnStateChangePostRecovery(): Unit = {
    snapshotConfig.snapshotEveryNEvents match {
      case Some(n) if n > 0 && lastSequenceNr % n == 0 => saveSnapshotStateIfAvailable()
      case _                                           => None
    }
  }

  /**
   * gets called post actor recovery completed (during actor start/restart)
   * and snapshot will be only saved if there is no snapshot offered so far
   * and number of events already greater than equal to 'snapshotEveryNEvents'
   */
  override def executeOnPostActorRecovery(): Unit = {
    snapshotConfig.snapshotEveryNEvents match {
      case Some(n) if n > 0 && lastSequenceNr >= n && ! isAnySnapshotOffered  => saveSnapshotStateIfAvailable()
      case _                                                                  =>
    }
  }

  /**
   * reason for overriding this method is that we wanted to make sure that
   * snapshots gets encrypted before it goes to the persistence layer
   * and so exposed other corresponding methods to be called to save snapshot
   *
   * @param snapshot state to be snapshotted
   */
  final override def saveSnapshot(snapshot: Any): Unit = {
    throw new RuntimeException("purposefully overridden to force calling 'saveSnapshotStateIfAvailable' instead")
  }

  /**
   * apart from getting called implicitly based on snapshot configuration
   * this method can be called manually/explicitly by implementing class
   * in case of no auto snapshot configuration
   */
  final def saveSnapshotStateIfAvailable(): Unit = {
    metricsWriter.get().gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_ATTEMPT_COUNT)
    snapshotState.foreach { state =>
      transformAndSaveSnapshot(state)
    }
  }

  /**
   * transformer used for state persistence
   */
  lazy val stateTransformer: Any <=> PersistentMsg = persistenceTransformerV1

  /**
   * transform (serialize, encrypt) and then save the given state as a snapshot
   *
   * @param state state to be snapshotted
   */
  private def transformAndSaveSnapshot(state: Any): Unit = {
    state match {
      case s: State =>
        val ts = stateTransformer.execute(s)
        if (ts.serializedSize <= maxItemSize) {
          PersistenceSerializerValidator.validate(ts, appConfig)
          super.saveSnapshot(ts)
          logger.info(s"[$persistenceId] save snapshot called (size: ${ts.serializedSize}), " +
            s"previous snapshot count (in current session): " + snapshotCount)
        } else {
          metricsWriter.get().gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_MAX_SIZE_EXCEEDED_CURRENT_COUNT)
          throttledLogger.info(SnapshotSizeExceeded(persistenceId),
            s"[$persistenceId] could not save snapshot because state size '${s.serializedSize}' " +
            s"exceeded max allowed size '$maxItemSize'")
          s.summary().foreach { stateSummary =>
            throttledLogger.info(SnapshotSizeExceededSummary(persistenceId),
              s"[$persistenceId] state summary: $stateSummary")
          }
        }
      case other    => throw new RuntimeException(s"'${other.getClass.getName}' is not a supported 'State'")
    }
  }

  lazy val maxItemSize: Int =
    appConfig.getIntOption(PERSISTENCE_SNAPSHOT_MAX_ITEM_SIZE_IN_BYTES)
      .getOrElse(190000)

  final override def receiveRecover: Receive =
    defaultSnapshotOfferReceiver orElse
      handleEvent

  def snapshotterCmdReceiver(actualReceiver: Receive): Receive =
    basePersistentCmdHandler(actualReceiver) orElse
      snapshotCallbackHandler orElse
      receiveUnhandled

  final override def receiveCommand: Receive = snapshotterCmdReceiver(cmdHandler)

  override def setNewReceiveBehaviour(receiver: Receive, discardOld: Boolean = true): Unit = {
    context.become(snapshotterCmdReceiver(receiver), discardOld)
  }

  /**
   * total snapshot count (offered+taken)
   */
  var snapshotCount: Int = 0
  var isAnySnapshotOffered: Boolean = false

  private val throttledLogger = new ThrottledLogger[SnapshotterLogMessages](logger, min_period = 30.minutes)
}

sealed trait SnapshotterLogMessages
case class SnapshotSizeExceeded(persistenceId: String) extends SnapshotterLogMessages
case class SnapshotSizeExceededSummary(persistenceId: String) extends SnapshotterLogMessages