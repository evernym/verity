package com.evernym.verity.protocol.engine.segmentedstate

import com.evernym.verity.protocol.engine.{DomainId, ProtoRef, StorageId}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentId, SegmentKey}
import com.evernym.verity.util.HashAlgorithm.SHA256_trunc16
import com.evernym.verity.util.HashUtil._

/**
  * This defines store primitives like segmentId and segmentAddress
  */

trait SegmentStoreStrategy {
  def calcSegmentId(segmentKey: SegmentKey): SegmentId

  /**
   * By default it should mix given either 'domainId' or 'storageId' with segmentId to make
   * it unique so that one protocol instance
   * should not be able to reach to a segment which belongs to a different protocol instance
   *
   * @param domainId domain id
   * @param storageId storage id
   * @param segmentId segment id - needs to be unique
   * @param protoRef protocol ref
   * @return
   */
  def calcSegmentAddress(domainId: DomainId,
                         storageId: StorageId,
                         segmentId: SegmentId,
                         protoRef: ProtoRef): SegmentAddress
}

object SegmentStoreStrategy {

  object Bucket_2_Legacy extends SegmentStoreStrategy {

    def calcSegmentId(segmentKey: SegmentKey): SegmentId = {
      (segmentKey.hashCode % 100).toString
    }

    /**
     * @param domainId domain id
     * @param storageId storage id
     * @param segmentId segment id - needs to be unique
     * @param protoRef protocol ref
     * @return
     */
    override def calcSegmentAddress(domainId: DomainId,
                                    storageId: StorageId,
                                    segmentId: SegmentId,
                                    protoRef: ProtoRef): SegmentAddress = s"$storageId-$segmentId"
  }

  object Bucket_4 extends SegmentStoreStrategy {

    def calcSegmentId(segmentKey: SegmentKey): SegmentId = {
      val strs = Seq(segmentKey)
      val hashed = safeIterMultiHash(SHA256_trunc16, strs).hex
      (hashed.hashCode % 10000).toString
    }

    /**
     * @param domainId domain id
     * @param storageId storage id
     * @param segmentId segment id - needs to be unique
     * @param protoRef protocol ref
     * @return
     */
    override def calcSegmentAddress(domainId: DomainId,
                                    storageId: StorageId,
                                    segmentId: SegmentId,
                                    protoRef: ProtoRef): SegmentAddress = s"$storageId-$segmentId"
  }

  object OneToOne extends SegmentStoreStrategy {

    def calcSegmentId(segmentKey: SegmentKey): SegmentId = {
      val strs = Seq(segmentKey)
      safeIterMultiHash(SHA256_trunc16, strs).hex
    }

    /**
     * @param domainId domain id
     * @param storageId storage id
     * @param segmentId segment id - needs to be unique
     * @param protoRef protocol ref
     * @return
     */
    override def calcSegmentAddress(domainId: DomainId,
                                    storageId: StorageId,
                                    segmentId: SegmentId,
                                    protoRef: ProtoRef): SegmentAddress = s"$domainId-$storageId-$protoRef-$segmentId"
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
     * @param domainId domain id
     * @param storageId storage id
     * @param segmentId segment id - needs to be unique
     * @param protoRef protocol ref
     * @return
     */
    override def calcSegmentAddress(domainId: DomainId,
                                    storageId: StorageId,
                                    segmentId: SegmentId,
                                    protoRef: ProtoRef): SegmentAddress = s"$domainId-$segmentId"
  }
}