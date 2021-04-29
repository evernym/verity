package com.evernym.verity.protocol.engine.segmentedstate

import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.StoredSegment
import com.evernym.verity.util.ParticipantUtil

import scala.util.Try

trait SegmentedStateContext[P,R,M,E,S,I]
  extends SegmentedStateContextApi { this: ProtocolContext[P,R,M,E,S,I] =>

  def segmentStoreStrategy: Option[SegmentStoreStrategy]

  def `segmentStoreStrategy_!`: SegmentStoreStrategy = segmentStoreStrategy.getOrElse(
    throw new RuntimeException("segmentStoreStrategy not provided")
  )

  def getDomainId: DomainId = getBackState.domainId.getOrElse(ParticipantUtil.DID(getRoster.selfId_!))

  /**
   * storeSegment implementation will store the given segments
   *  (either to actor based storage or some external storage based on size constraints)
   * and as with any other async service, the 'segmentStorageService.storeSegment' async op
   * will be executed with AsyncOpRunner which will make sure to finalize things once
   * async op is completed
   */
  final def storeSegment(segmentKey: SegmentKey, segment: Any)(handler: Try[StoredSegment] => Unit): Unit = {
    val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
    val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
    val segmentAddress = segmentStoreStrategy_!.calcSegmentAddress(domainId, pinstId, segmentId)
    segmentStore.storeSegment(segmentAddress, segmentKey, segment, dataRetentionPolicy)(handler)
  }


  final def withSegment[T](segmentKey: SegmentKey)(handler: Try[Option[T]] => Unit): Unit = {
    val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
    val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
    val segmentAddress = segmentStoreStrategy_!.calcSegmentAddress(domainId, pinstId, segmentId)
    segmentStore.withSegment(segmentAddress, segmentKey, dataRetentionPolicy)(handler)
  }

}

trait SegmentedStateContextApi {
  /**
    * stores given segment with segment key
    * @param segmentKey segment key associated with given segment
    * @param segment segment to be stored
    * @param handler to process result of storage
    */
  def storeSegment(segmentKey: SegmentKey, segment: Any)(handler: Try[StoredSegment] => Unit): Unit

  /**
   * gets stored segment based on provided segment key
   * @param segmentKey
   * @param handler
   * @tparam T
   */
  def withSegment[T](segmentKey: SegmentKey)(handler: Try[Option[T]] => Unit): Unit
}
