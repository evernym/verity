package com.evernym.verity.actor

import akka.persistence.SnapshotSelectionCriteria
import com.evernym.verity.util.ReqMsgContext

package object persistence {

  /**
   *
   * @param snapshotEveryNEvents optional, number of event persistence after which actor will save snapshot
   * @param keepNSnapshots how many snapshots to retain, if empty, it will retain all snapshots
   *                       this configuration is independent of 'snapshotEveryNEvents'
   *                       as it can be used even if implementing class is explicitly saving snapshot
   * @param deleteEventsOnSnapshot decides if older events than recent snapshot should be deleted or not
   *                               this configuration is independent of 'snapshotEveryNEvents'
   *                               as it can be used even if implementing class is explicitly saving snapshot
   */
  case class SnapshotConfig(snapshotEveryNEvents: Option[Int],
                            keepNSnapshots: Option[Int],
                            deleteEventsOnSnapshot: Boolean) {
    //if snapshotEveryNEvents is non zero, then keepNSnapshot must be supplied
    require(! (snapshotEveryNEvents.exists(_ > 0) && keepNSnapshots.forall(_ <= 0 )),
      "'snapshotEveryNEvents' and 'keepNSnapshots' are conflicting")

    //if no auto snapshotting is on (snapshotEveryNEvents is empty or less than equal to 0)
    //then keepNSnapshot should be 1 only
    //else otherwise there won't be a way to know when snapshot was taken
    //and hence no logic to decide which snapshots to keep and which to delete
    require(! (snapshotEveryNEvents.forall(_ <= 0 ) && keepNSnapshots.exists(_ > 1)),
      "'keepNSnapshots' and 'snapshotEveryNEvents' are conflicting")

    def getDeleteSnapshotCriteria(curSnapshotSeqNr: Long): Option[SnapshotSelectionCriteria] = {
      (keepNSnapshots, snapshotEveryNEvents) match {
        case (Some(keepNSnapshot), Some(snapshotEveryNEvent)) =>
          val minSequenceNumber = curSnapshotSeqNr - (keepNSnapshot * snapshotEveryNEvent)
          val maxSequenceNumber = curSnapshotSeqNr - ((keepNSnapshot-1) * snapshotEveryNEvent) - 1
          if (minSequenceNumber > 0 && maxSequenceNumber > 0) {
            Option(SnapshotSelectionCriteria(maxSequenceNr = maxSequenceNumber, minSequenceNr = minSequenceNumber))
          } else None
        case (Some(keepNSnapshot), None) if curSnapshotSeqNr >= 1 && keepNSnapshot == 1 =>   //manual snapshotting
          Option(SnapshotSelectionCriteria(maxSequenceNr = curSnapshotSeqNr-keepNSnapshot))
        case _ => None
      }
    }
  }

  case object GetActorDetail extends ActorMessageObject
  case class ActorDetail(persistenceId: String, totalPersistedEvents: Int, totalRecoveredEvents: Int) extends ActorMessageClass
  case class PostRecoveryActorInitFailed(error: Throwable) extends ActorMessageClass
  case object PostRecoveryActorInitSucceeded extends ActorMessageObject
  case object ActorInitPostRecoveryFailed extends ActorMessageObject

  case class InternalReqHelperData(reqMsgContext: ReqMsgContext)

  def stdPersistenceId(entityName: String, entityId: String) = s"$entityName-$entityId"
}
