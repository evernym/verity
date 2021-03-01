package com.evernym.verity.protocol.engine.segmentedstate

trait SegmentedStateProtoDef[S] {

  /**
    * if any protocol definition wants to use segmented state
    * it will have to override this method and provide a name for that state
    * we are assuming one segmented state to begin with, but in future, extending it to support
    * multiple segmented states should be easy
    */
  def segmentedStateName: Option[String] = None

}

