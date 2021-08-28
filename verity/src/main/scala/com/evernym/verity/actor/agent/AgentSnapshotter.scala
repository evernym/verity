package com.evernym.verity.actor.agent

import com.evernym.verity.actor.State
import com.evernym.verity.actor.agent.state.base.AgentStateInterface
import com.evernym.verity.actor.persistence.{BasePersistentActor, SnapshotterExt}
import com.evernym.verity.observability.metrics.CustomMetrics.AS_SERVICE_DYNAMODB_SNAPSHOT_THREAD_CONTEXT_SIZE_EXCEEDED_CURRENT_COUNT

/**
 * a base agent snapshotter trait to be added/included in different agent actor
 * to add snapshot capability
 * (corresponding config needs to be enabled in reference.conf and
 * also adding snapshot state object mapping in DefaultObjectCodeMapper)
 *
 * @tparam T
 */
trait AgentSnapshotter[T <: State with AgentStateInterface]
  extends SnapshotterExt[T] { this: BasePersistentActor =>

  var state: T

  /**
   * a snapshot handler (used during actor recovery)
   *
   * @return
   */
  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case as => state = as.asInstanceOf[T]
  }

  /**
   * state to be snapshotted
   *
   * @return
   */
  override def snapshotState: Option[T] = {
    //as the thread context migration related information is not part of the agent state,
    // snapshot should be only taken when all thread contexts are migrated
    if (state.currentThreadContexts.isEmpty && isThreadContextMigrationFinished) {
      Option(state)
    } else {
      metricsWriter.gaugeIncrement(AS_SERVICE_DYNAMODB_SNAPSHOT_THREAD_CONTEXT_SIZE_EXCEEDED_CURRENT_COUNT)
      None
    }
  }

  def isThreadContextMigrationFinished: Boolean
}