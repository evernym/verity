package com.evernym.verity.actor

import com.evernym.verity.util.ReqMsgContext

package object persistence {

  /**
   *
   * @param allowOnlyEvents currently not used except for validation
   * @param allowOnlySnapshots currently not used except for validation
   * @param autoSnapshotAfterEvents optional number of events after which actor will save snapshot
   * @param deleteEventsOlderThanRecentSnapshot decides if older events than recent snapshot should be deleted or not
   * @param deleteSnapshotsOlderThanRecentSnapshot decides if older snapshots (except recent one) should be deleted or not
   */
  case class PersistenceConfig(allowOnlyEvents: Boolean,
                               allowOnlySnapshots: Boolean,
                               autoSnapshotAfterEvents: Option[Int],
                               deleteEventsOlderThanRecentSnapshot: Boolean,
                               deleteSnapshotsOlderThanRecentSnapshot: Boolean) {
    require(! (allowOnlyEvents && allowOnlySnapshots), "'allowOnlyEvents' and 'allowOnlySnapshots' are conflicting")
    require(! (allowOnlyEvents && autoSnapshotAfterEvents.isDefined), "'allowOnlyEvents' and 'autoSnapshotAfterEvents' are conflicting")
    require(! (allowOnlySnapshots && autoSnapshotAfterEvents.isDefined), "'allowOnlySnapshots' and 'autoSnapshotAfterEvents' are conflicting")
  }

  case object GetActorDetail extends ActorMessageObject
  case class ActorDetail(persistenceId: String, totalPersistedEvents: Int, totalRecoveredEvents: Int) extends ActorMessageClass
  case class ActorInitPostRecoveryFailed (error: Throwable) extends ActorMessageClass
  case object ActorInitializedPostRecovery extends ActorMessageObject

  case class InternalReqHelperData(reqMsgContext: ReqMsgContext)
}
