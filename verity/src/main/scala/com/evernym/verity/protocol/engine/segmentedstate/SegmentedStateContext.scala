package com.evernym.verity.protocol.engine.segmentedstate

import com.evernym.verity.protocol.engine.events.{SegmentRemoved, SegmentStored}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes._
import com.evernym.verity.protocol.engine._
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.StoredSegment
import com.evernym.verity.protocol.engine.context.ProtocolContext
import com.evernym.verity.util.ParticipantUtil
import com.evernym.verity.util2.Exceptions

import java.util.UUID
import scala.util.{Failure, Success, Try}

trait SegmentedStateContext[P,R,M,E,S,I]
  extends SegmentedStateContextApi { this: ProtocolContext[P,R,M,E,S,I] =>

  def `segmentStoreStrategy_!`: SegmentStoreStrategy = definition.segmentStoreStrategy.getOrElse(
    throw new RuntimeException("segmentStoreStrategy not provided")
  )

  def getDomainId: DomainId = getBackState.domainId getOrElse ParticipantUtil.DID(getRoster.selfId_!)

  def getProtoRef: ProtoRef = definition.protoRef

  /**
   * This cache should only live in memory. No long-term persistence because of sensitive Data Retention Policy data.
   */
  var segmentCache: Map[SegmentKey, Any] = Map.empty

  /**
   * storeSegment implementation will store the given segments
   * (either to actor based storage or some external storage based on size constraints)
   * and as with any other async service, the 'segmentStorageService.storeSegment' async op
   * will be executed with AsyncOpRunner which will make sure to finalize things once
   * async op is completed
   */
  final def storeSegment(segmentKey: SegmentKey, segment: Any)(handler: Try[StoredSegment] => Unit): Unit = {
    val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
    val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
    val address = segmentStoreStrategy_!.calcSegmentAddress(domainId, _storageId_!, segmentId, getProtoRef)
    segmentStore.storeSegment(address, segmentKey, segment, dataRetentionPolicy.map(_.elements.expiryDaysStr)) { result =>
      result match {
        case Success(_) =>
          apply(SegmentStored(segmentKey))
          segmentCache += (segmentKey -> segment)
        case Failure(e) =>
          logger.error(s"error while storing segment: " +
            s"protoRef: $getProtoRef, " +
            s"error: ${Exceptions.getErrorMsg(e)}")
      }
      handler(result)
    }
  }

  final def withSegment[T](segmentKey: SegmentKey)(handler: Try[Option[T]] => Unit): Unit = {
    segmentCache.get(segmentKey) match {
      case Some(s) =>
        handler(Success(Some(s.asInstanceOf[T])))
      case None =>
        val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
        val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
        val address = segmentStoreStrategy_!.calcSegmentAddress(domainId, _storageId_!, segmentId, getProtoRef)
        segmentStore.withSegment(address, segmentKey, dataRetentionPolicy.map(_.elements.expiryDaysStr)) { result: Try[Option[T]] =>
          result match {
            case Success(Some(_)) => //nothing to do
            case Success(None)  =>
              logger.info(s"requested segmented data not found (never stored or already removed or expired): " +
                s"protoRef: $getProtoRef, " +
                s"policy: ${dataRetentionPolicy.map(_.configString)}")
            case Failure(e) =>
              logger.error(s"error while retrieving segment: " +
                s"protoRef: $getProtoRef, " +
                s"error: " + {Exceptions.getStackTraceAsString(e)})
          }
          handler(result)
        }
    }
  }

  final def removeSegment(segmentKey: SegmentKey)(handler: Try[SegmentKey] => Unit): Unit = {
    val segmentId = segmentStoreStrategy_!.calcSegmentId(segmentKey)
    val domainId = Try(getDomainId).getOrElse(withShadowAndRecord(getDomainId))
    val address = segmentStoreStrategy_!.calcSegmentAddress(domainId, _storageId_!, segmentId, getProtoRef)
    segmentStore.removeSegment(address, segmentKey, dataRetentionPolicy.map(_.elements.expiryDaysStr)) { result =>
      result match {
        case Success(_) =>
          apply(SegmentRemoved(segmentKey))
          segmentCache -= segmentKey
        case Failure(e) =>
          logger.error(s"error while removing segment: " +
            s"protoRef: $getProtoRef, " +
            s"error: " + {Exceptions.getErrorMsg(e)})
      }
      handler(result)
    }
  }
}

trait SegmentedStateContextApi {
  /**
    * stores given segment with segment key
    * @param segmentKey segment key associated with given segment
    * @param segment segment to be stored
    * @param handler to process result of storage
    */
  def storeSegment(segmentKey: SegmentKey=UUID.randomUUID().toString,
                   segment: Any)
                  (handler: Try[StoredSegment] => Unit): Unit

  /**
   * gets stored segment based on provided segment key
   * @param segmentKey
   * @param handler
   * @tparam T
   */
  def withSegment[T](segmentKey: SegmentKey)(handler: Try[Option[T]] => Unit): Unit
}
