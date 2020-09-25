package com.evernym.verity.protocol.engine.segmentedstate

import com.evernym.verity.actor.StorageReferenceStored

trait SegmentedStateMsg

object SegmentedStateTypes {

  /**
    * A Segment is a portion of the state, that is, one of the items in the collection.
    * E.g., PhoneBookEntry(firstname, lastname, phonenumber)
    */
  type Segment = Any


  /**
    * Segments are logically divided by some key, called a Segment Key.
    * A segment key can always be extracted from a Segment. E.g., (firstname + lastname): String
    */
  type SegmentKey = String


  /**
   * A segment id is unique id for a segmented state which is derived from SegmentKey
   * It is used to identify a segment uniquely at the segment store
   */
  type SegmentId = String


  /**
    * A segmentAddress is derived from pinst id and SegmentId to make it unique
    * This is address where segment will be stored/looked-up
    */
  type SegmentAddress = String

  case class Write(segmentAddress: SegmentAddress, key: SegmentKey, value: Any) extends SegmentedStateMsg

  case class WriteStorage(segmentAddress: SegmentAddress, key: SegmentKey, value: Any) extends SegmentedStateMsg

  case class Read(segmentAddress: SegmentAddress, key: SegmentKey) extends SegmentedStateMsg

  case class ReadStorage(segmentAddress: SegmentAddress, key: SegmentKey, storageRef: StorageReferenceStored) extends SegmentedStateMsg
}
