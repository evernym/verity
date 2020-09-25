package com.evernym.verity.protocol.engine.segmentedstate

import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.SegmentKey
import com.evernym.verity.protocol.engine.util.?=>

trait SegmentedStateProtoDef[S] {

  /**
    * if any protocol definition wants to use segmented state
    * it will have to override this method and provide a name for that state
    * we are assuming one segmented state to begin with, but in future, extending it to support
    * multiple segmented states should be easy
    */
  def segmentedStateName: Option[String] = None

  /**
    * used when need to retrieve stored segment based on incoming control/protocol message
    * this helps to determine for which incoming messages, segments needs to be retrieved
    * before processing the message
    * @tparam A any control or protocol message
    * @tparam B protocol state
    * @tparam C SegmentKey belonging to the incoming message
    * @return
    */
  def segmentRetrieval[A, B >:S, C >: SegmentKey]: (A, B) ?=> C = {
    if (segmentedStateName.isEmpty) {
      throw new RuntimeException("segmentRetrieval was called with no segmentedStateName defined")
    } else {
      throw new RuntimeException("segmentedStateName is defined, yet segmentRetrieval was not overridden; segmentRetrieval must be defined if segmentedStateName is")
    }
  }

}

