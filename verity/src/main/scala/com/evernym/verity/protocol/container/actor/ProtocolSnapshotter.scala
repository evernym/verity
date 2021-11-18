package com.evernym.verity.protocol.container.actor

import com.evernym.verity.actor.persistence.{BasePersistentActor, SnapshotterExt}
import com.evernym.verity.protocol.engine.context.ProtocolContext

trait ProtocolSnapshotter[S]
  extends SnapshotterExt[S] { this: ProtocolContext[_,_,_,_,S,_] with BasePersistentActor =>

  /**
   * a snapshot handler (used during actor recovery)
   *
   * @return
   */
  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case as =>
      state = as.asInstanceOf[S]
      log.debug(s"[$persistenceId] snapshot state received and applied (${definition.protoRef.toString}):" + state)
  }

  /**
   * should be overridden by implementing class and return optional
   * state which should be stored as snapshot, this is only used
   * if 'persistenceConfig.snapshotEveryNEvents' is configured as non empty and greater than zero
   * or explicitly trying to save snapshot (by calling 'saveSnapshotStateIfAvailable' method)
   *
   * @return
   */
  override def snapshotState: Option[S] = {
    Some(getState)
  }
}
