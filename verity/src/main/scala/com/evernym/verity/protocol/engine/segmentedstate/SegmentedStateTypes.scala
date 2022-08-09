package com.evernym.verity.protocol.engine.segmentedstate


object SegmentedStateTypes {

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

}
