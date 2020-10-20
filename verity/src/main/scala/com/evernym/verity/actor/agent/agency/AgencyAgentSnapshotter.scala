package com.evernym.verity.actor.agent.agency

import com.evernym.verity.actor.persistence.SnapshotterExt

trait AgencyAgentSnapshotter extends SnapshotterExt[AgencyAgentState] { this: AgencyAgent =>


  /**
   * state to be snapshotted
   *
   * @return
   */
  override def snapshotState: Option[AgencyAgentState] = Option(state)

  /**
   * a snapshot handler (used during actor recovery)
   *
   * @return
   */
  override def receiveSnapshot: PartialFunction[Any, Unit] = {
    case as: AgencyAgentState => state = as
  }
}