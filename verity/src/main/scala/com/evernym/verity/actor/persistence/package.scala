package com.evernym.verity.actor

import com.evernym.verity.util.ReqMsgContext

package object persistence {

  /**
   *
   * @param allowOnlyEvents currently not used except for validation
   * @param allowOnlySnapshots currently not used except for validation
   * @param snapshotEveryNEvents optional, number of event persistence after which actor will save snapshot
   * @param keepNSnapshots how many snapshots to retain, if empty, it will retain all snapshots
   *                       this configuration is independent of 'snapshotEveryNEvents'
   *                       as it can be used even if implementing class is explicitly saving snapshot
   * @param deleteEventsOnSnapshot decides if older events than recent snapshot should be deleted or not
   *                               this configuration is independent of 'snapshotEveryNEvents'
   *                               as it can be used even if implementing class is explicitly saving snapshot
   */
  case class PersistenceConfig(allowOnlyEvents: Boolean,
                               allowOnlySnapshots: Boolean,
                               snapshotEveryNEvents: Option[Int],
                               keepNSnapshots: Option[Int],
                               deleteEventsOnSnapshot: Boolean) {
    require(! (allowOnlyEvents && allowOnlySnapshots),
      "'allowOnlyEvents' and 'allowOnlySnapshots' are conflicting")
    require(! (allowOnlyEvents && snapshotEveryNEvents.exists(_ > 0)),
      "'allowOnlyEvents' and 'snapshotEveryNEvents' are conflicting")
    require(! (allowOnlySnapshots && snapshotEveryNEvents.exists(_ > 0)),
      "'allowOnlySnapshots' and 'snapshotEveryNEvents' are conflicting")
  }

  case object GetActorDetail extends ActorMessageObject
  case class ActorDetail(persistenceId: String, totalPersistedEvents: Int, totalRecoveredEvents: Int) extends ActorMessageClass
  case class ActorInitPostRecoveryFailed (error: Throwable) extends ActorMessageClass
  case object ActorInitializedPostRecovery extends ActorMessageObject

  case class InternalReqHelperData(reqMsgContext: ReqMsgContext)

  def stdPersistenceId(entityName: String, entityId: String) = s"$entityName-$entityId"
}
