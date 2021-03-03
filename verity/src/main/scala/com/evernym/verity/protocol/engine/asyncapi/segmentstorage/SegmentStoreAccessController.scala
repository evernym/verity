package com.evernym.verity.protocol.engine.asyncapi.segmentstorage

import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AsyncOpRunner, BaseAccessController}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}

import scala.util.Try

class SegmentStoreAccessController(segmentStoreImpl: SegmentStoreAccess)
                                  (implicit val asyncOpRunner: AsyncOpRunner)
  extends SegmentStoreAccess
    with BaseAccessController {

  val accessRights: Set[AccessRight] = Set.empty

  //NOTE: we have 'access rights' mechanisms for other such/similar async apis,
  // but that is not the case with this api (so far) and hence in below method implementation
  // instead of calling 'runIfAllowed', we are directly calling 'withAsyncOpRunner'
  override def storeSegment(segmentAddress: SegmentAddress, segmentKey: SegmentKey, segment: Any)
                           (handler: Try[StoredSegment] => Unit): Unit =
    withAsyncOpRunner(
      {segmentStoreImpl.storeSegment(segmentAddress, segmentKey, segment)(handler)},
      handler
    )

  //NOTE: we have 'access rights' mechanisms for other such/similar async apis,
  // but that is not the case with this api (so far) and hence in below method implementation
  // instead of calling 'runIfAllowed', we are directly calling 'withAsyncOpRunner'
  override def withSegment[T](segmentAddress: SegmentAddress, segmentKey: SegmentKey)
                             (handler: Try[Option[T]] => Unit): Unit =
    withAsyncOpRunner(
      {segmentStoreImpl.withSegment(segmentAddress, segmentKey)(handler)},
      handler
    )
}