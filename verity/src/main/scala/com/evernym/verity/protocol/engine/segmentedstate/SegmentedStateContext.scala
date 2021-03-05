package com.evernym.verity.protocol.engine.segmentedstate

import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.util.ParticipantUtil

import scala.util.Try

trait SegmentedStateContext[P,R,M,E,S,I]
  extends SegmentedStateContextApi { this: ProtocolContext[P,R,M,E,S,I] =>

  /**
    * pending segments used to save off segment for later persistence at the end of specific transaction
    */
  protected var pendingSegments = List.empty[PendingSegment]

  def segmentStoreStrategy: Option[SegmentStoreStrategy]

  def `segmentStoreStrategy_!`: SegmentStoreStrategy = segmentStoreStrategy.getOrElse(
    throw new RuntimeException("segmentStoreStrategy not provided")
  )

  def getDomainId: DomainId = getBackState.domainId.getOrElse(ParticipantUtil.DID(getRoster.selfId_!))

  def addToPendingList(ps: PendingSegment): Unit = {
    pendingSegments = pendingSegments :+ ps
  }

  def removeFromPendingList(segmentAddress: SegmentAddress): Unit = {
    pendingSegments = pendingSegments.filterNot(_.segmentAddress == segmentAddress)
  }

  final def storeSegment(segmentKey: SegmentKey, segment: Any): Unit = {
    val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
    val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
    val segmentAddress = segmentStoreStrategy_!.calcSegmentAddress(domainId, pinstId, segmentId)
    addToPendingList(PendingSegment(segmentAddress, segmentKey, segment))
  }

  final def withSegment[T](segmentKey: SegmentKey)(handler: Try[Option[T]] => Unit): Unit = {
    val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
    val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
    val segmentAddress = segmentStoreStrategy_!.calcSegmentAddress(domainId, pinstId, segmentId)
    segmentStore.withSegment(segmentAddress, segmentKey)(handler)
  }

}

trait SegmentedStateContextApi {
  /**
    * stores given segment with segment key
    * @param segmentKey segment key associated with given segment
    * @param segment segment to be stored
    */
  def storeSegment(segmentKey: SegmentKey, segment: Any): Unit

  /**
   * gets stored segment based on provided segment key
   * @param segmentKey
   * @param handler
   * @tparam T
   */
  def withSegment[T](segmentKey: SegmentKey)(handler: Try[Option[T]] => Unit): Unit
}
