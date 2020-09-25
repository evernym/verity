package com.evernym.verity.protocol.engine.segmentedstate

import com.evernym.verity.protocol.engine.{DomainId, PinstId}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentId, SegmentKey}
import com.evernym.verity.util.HashAlgorithm.SHA256_trunc16
import com.evernym.verity.util.HashUtil._

/**
  * This defines store primitives like segmentId and segmentAddress
  */

trait SegmentStoreStrategy {
  def calcSegmentId(segmentKey: SegmentKey): SegmentId

  /**
    * By default it should mix given 'pinstid' with segmentId to make it unique so that one protocol instance
    * should not be able to reach to a segment which belongs to a different protocol instance
    *
    * @param pinstId protocol instance id
    * @param domainId domain id
    * @param segmentId segment id
    * @return
    */
  def calcSegmentAddress(pinstId: PinstId, domainId: DomainId, segmentId: SegmentId): SegmentAddress = {
    pinstId + "-" + segmentId
  }
}

object SegmentStoreStrategy {

  object Bucket_2_Legacy extends SegmentStoreStrategy {

    def calcSegmentId(segmentKey: SegmentKey): SegmentId = {
      (segmentKey.hashCode % 100).toString
    }
  }

  object Bucket_4 extends SegmentStoreStrategy {

    def calcSegmentId(segmentKey: SegmentKey): SegmentId = {
      val strs = Seq(segmentKey)
      val hashed = safeIterMultiHash(SHA256_trunc16, strs).hex
      (hashed.hashCode % 10000).toString
    }
  }

  object OneToOne extends SegmentStoreStrategy {

    def calcSegmentId(segmentKey: SegmentKey): SegmentId = {
      val strs = Seq(segmentKey)
      safeIterMultiHash(SHA256_trunc16, strs).hex
    }
  }

  object OneToOneDomain extends SegmentStoreStrategy {

    def calcSegmentId(segmentKey: SegmentKey): SegmentId = {
      val strs = Seq(segmentKey)
      safeIterMultiHash(SHA256_trunc16, strs).hex
    }

    /**
     * It should mix given 'domainId' with segmentId to make it unique so that one protocol instance
     * should not be able to reach to a segment which belongs to a different domain
     *
     * It is possible that domainId is None in some old protocols. This is not supported.
     *
     * @param pinstId protocol instance id
     * @param domainId domain id
     * @param segmentId segment id
     * @return
     */
    override def calcSegmentAddress(pinstId: PinstId, domainId: DomainId, segmentId: SegmentId): SegmentAddress = {
      domainId + '-' + segmentId
    }
  }
}
