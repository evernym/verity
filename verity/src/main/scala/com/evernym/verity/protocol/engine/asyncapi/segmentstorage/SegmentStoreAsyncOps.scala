package com.evernym.verity.protocol.engine.asyncapi.segmentstorage

import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}

trait SegmentStoreAsyncOps {

  def runStoreSegment(segmentAddress: SegmentAddress,
                      segmentKey: SegmentKey,
                      segment: Any,
                      retentionPolicy: Option[String] = None): Unit

  def runWithSegment[T](segmentAddress: SegmentAddress,
                        segmentKey: SegmentKey,
                        retentionPolicy: Option[String] = None): Unit

  def runDeleteSegment(segmentAddress: SegmentAddress,
                       segmentKey: SegmentKey,
                       retentionPolicy: Option[String]=None): Unit
}
