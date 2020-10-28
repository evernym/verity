package com.evernym.verity.actor.agent

import com.evernym.verity.actor.State
import com.evernym.verity.actor.persistence.{BasePersistentActor, SnapshotterExt}

/**
 * a base agent snapshotter trait to be added/included in different agent actor
 * to add snapshot capability
 * (corresponding config needs to be enabled in reference.conf and
 * also adding snapshot state object mapping in DefaultObjectCodeMapper)
 *
 * @tparam T
 */
trait AgentSnapshotter[T <: State] extends SnapshotterExt[T] { this: BasePersistentActor =>

  var state: T

  /**
   * state to be snapshotted
   *
   * @return
   */
  override def snapshotState: Option[T] = Option(state)

  /**
   * a snapshot handler (used during actor recovery)
   *
   * @return
   */
  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case as: T => state = as
  }
}