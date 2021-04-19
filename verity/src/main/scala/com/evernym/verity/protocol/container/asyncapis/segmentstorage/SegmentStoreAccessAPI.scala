package com.evernym.verity.protocol.container.asyncapis.segmentstorage

import akka.pattern.ask
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.actor.{ForIdentifier, StorageInfo, StorageReferenceStored}
import com.evernym.verity.actor.segmentedstates.{GetSegmentedState, SaveSegmentedState, SegmentedStateStore, ValidationError}
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.asyncapi.segmentstorage.{SegmentStoreAccess, StoredSegment}
import com.evernym.verity.protocol.engine.asyncapi.{AccessRight, AsyncOpRunner, BaseAccessController}
import com.evernym.verity.protocol.engine.{BaseAsyncOpExecutorImpl, ProtoRef}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}
import com.evernym.verity.storage_services.StorageAPI
import com.typesafe.scalalogging.Logger
import scalapb.GeneratedMessage

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class SegmentStoreAccessAPI(storageAPI: StorageAPI,
                            protoRef: ProtoRef,
                            segmentedStateName: Option[String])
                           (implicit val asyncAPIContext: AsyncAPIContext,
                              val asyncOpRunner: AsyncOpRunner)
  extends SegmentStoreAccess
    with BaseAccessController
    with BaseAsyncOpExecutorImpl {

  private val logger: Logger = LoggingUtil.getLoggerByClass(getClass)
  private val MAX_SEGMENT_SIZE = 400000   //TODO: shouldn't this be little less than 400 KB?

  private def isGreaterThanMaxSegmentSize(data: GeneratedMessage): Boolean = !isLessThanMaxSegmentSize(data)

  private def isLessThanMaxSegmentSize(data: GeneratedMessage): Boolean = data.serializedSize < MAX_SEGMENT_SIZE

  private def sendToSegmentedRegion(segmentAddress: SegmentAddress, cmd: Any)
                                   (implicit ec: ExecutionContext): Future[StoredSegment] = {
    val typeName = SegmentedStateStore.buildTypeName(protoRef, segmentedStateName.get)
    val segmentedStateRegion = ClusterSharding.get(context.system).shardRegion(typeName)
    val fut = segmentedStateRegion ? ForIdentifier(segmentAddress, cmd)
    fut map {
      case Some(v: Any)         => StoredSegment(segmentAddress, Option(v))
      case None                 => StoredSegment(segmentAddress, None)
      case ve: ValidationError  => throw new RuntimeException("segment validation error: " + ve.error)
    }
  }

  private def blobStoreBucket: String = appConfig
    .config
    .getConfig("verity.blob-store")
    .getString("bucket-name")

  private def storeInBlobStore(blob: BlobSegment, data: GeneratedMessage)
                              (implicit ec: ExecutionContext): Future[StoredSegment] = {
    logger.debug(s"storing storage state: $data")
    storageAPI.put(blob.bucketName, blob.key, data.toByteArray).flatMap {
      case storageInfo: StorageInfo =>
        logger.debug(s"data stored at: ${storageInfo.endpoint}")
        val storageReference = StorageReferenceStored(storageInfo.`type`, SegmentedStateStore.eventCode(data), Some(storageInfo))
        sendToSegmentedRegion(blob.segmentAddress, SaveSegmentedState(blob.segmentKey, storageReference))
      case value =>
        // TODO the type constraint should make this case un-needed
        val msg = "storing information is not a excepted type, unable to process it " +
          s"-- it is ${value.getClass.getSimpleName}"
        logger.error(msg)
        throw new RuntimeException("error during storing segment: " + msg)
    }
  }

  private def saveSegmentedState(segmentAddress: SegmentAddress,
                                 segmentKey: SegmentKey,
                                 segment: Any,
                                 retentionPolicy: Option[String])
                                (implicit ec: ExecutionContext): Future[StoredSegment] = {
    val fut = segment match {
      case data: GeneratedMessage if isGreaterThanMaxSegmentSize(data) || retentionPolicy.isDefined =>
        logger.debug(s"storing $data in segment storage")
        storeInBlobStore(BlobSegment(blobStoreBucket, segmentAddress, segmentKey, retentionPolicy), data)

      case segmentData: GeneratedMessage if isLessThanMaxSegmentSize(segmentData) && retentionPolicy.isEmpty =>
        val cmd = SaveSegmentedState(segmentKey, segmentData)
        sendToSegmentedRegion(segmentAddress, cmd)
    }

    fut map {
      case ss @ StoredSegment(_, Some(_)) => ss.copy(segment = Option(segment))
      case other                          => other
    }
  }

  private def readFromBlobStore[T](blob: BlobSegment,
                                   storageRef: StorageReferenceStored)
                                  (implicit ec: ExecutionContext): Future[Option[T]] = {
    storageAPI.get(blob.bucketName, blob.key).map { data: Array[Byte]  =>
      Option(SegmentedStateStore.buildEvent(storageRef.eventCode, data).asInstanceOf[T])
    }
  }

  private def readSegmentedState(segmentAddress: SegmentAddress, segmentKey: SegmentKey, retentionPolicy: Option[String])
                                (implicit ec: ExecutionContext): Future[Any] = {
    val cmd = GetSegmentedState(segmentKey)
    sendToSegmentedRegion(segmentAddress, cmd).flatMap {
      case StoredSegment(segmentAddress, Some(srs: StorageReferenceStored)) =>
        readFromBlobStore(BlobSegment(blobStoreBucket, segmentAddress, segmentKey, retentionPolicy), srs)
      case StoredSegment(_, Some(segment: Any)) => Future.successful(Option(segment))
      case StoredSegment(_, None)               => Future.successful(None)
      case other  => throw new RuntimeException("unexpected response while retrieving segment: " + other)
    }
  }

  override def storeSegment(segmentAddress: SegmentAddress,
                            segmentKey: SegmentKey,
                            segment: Any,
                            retentionPolicy: Option[String]=None) (handler: Try[StoredSegment] => Unit): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec: ExecutionContext => saveSegmentedState(segmentAddress, segmentKey, segment, retentionPolicy) }
    )
  }

  override def withSegment[T](segmentAddress: SegmentAddress,
                              segmentKey: SegmentKey,
                              retentionPolicy: Option[String]=None) (handler: Try[Option[T]] => Unit): Unit = {
    withAsyncOpExecutorActor(
      { implicit ec: ExecutionContext => readSegmentedState(segmentAddress, segmentKey, retentionPolicy) }
    )
  }

  override def accessRights: Set[AccessRight] = Set.empty
}

case class BlobSegment(bucketName: String,
                       segmentAddress: SegmentAddress,
                       segmentKey: SegmentKey,
                       lifecycle: Option[String]) {
  def key: String = lifecycle.map(x => s"$x/").getOrElse("") + segmentAddress + segmentKey
}
