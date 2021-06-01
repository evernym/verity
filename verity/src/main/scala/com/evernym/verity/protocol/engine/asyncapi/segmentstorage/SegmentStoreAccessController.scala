package com.evernym.verity.protocol.engine.asyncapi.segmentstorage

import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AsyncOpRunner, BaseAccessController}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}

import scala.util.Try

class SegmentStoreAccessController(segmentStoreExecutor: SegmentStoreAsyncOps)
                                  (implicit val asyncOpRunner: AsyncOpRunner)
  extends SegmentStoreAccess
    with BaseAccessController {

  val accessRights: Set[AccessRight] = Set.empty

  //NOTE: we have 'access rights' mechanisms for other such/similar async apis,
  // but that is not the case with this api (so far) and hence in below method implementation
  // instead of calling 'runIfAllowed', we are directly calling 'withAsyncOpRunner'
  override def storeSegment(segmentAddress: SegmentAddress,
                            segmentKey: SegmentKey,
                            segment: Any,
                            retentionPolicy: Option[String]=None) (handler: Try[StoredSegment] => Unit): Unit =
    withAsyncOpRunner(
      {segmentStoreExecutor.runStoreSegment(segmentAddress, segmentKey, segment, retentionPolicy)},
      handler
    )

  //NOTE: we have 'access rights' mechanisms for other such/similar async apis,
  // but that is not the case with this api (so far) and hence in below method implementation
  // instead of calling 'runIfAllowed', we are directly calling 'withAsyncOpRunner'
  override def withSegment[T](segmentAddress: SegmentAddress,
                              segmentKey: SegmentKey,
                              retentionPolicy: Option[String]=None) (handler: Try[Option[T]] => Unit): Unit =
    withAsyncOpRunner(
      {segmentStoreExecutor.runWithSegment(segmentAddress, segmentKey, retentionPolicy)},
      handler
    )

  override def removeSegment(segmentAddress: SegmentAddress, segmentKey: SegmentKey)
                            (handler: Try[Unit] => Unit): Unit = ???
}