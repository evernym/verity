package com.evernym.verity.protocol.engine.segmentedstate

trait SegmentedStateProtoDef[S] {

  /**
    * if any protocol definition wants to use segmented state
    * it will have to override this method and provide a strategy
    */
  def segmentStoreStrategy: Option[SegmentStoreStrategy] = None
}

