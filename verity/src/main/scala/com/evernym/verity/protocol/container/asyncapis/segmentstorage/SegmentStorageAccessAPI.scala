package com.evernym.verity.protocol.container.asyncapis.segmentstorage

import akka.pattern.ask
import akka.actor.ActorContext
import akka.cluster.sharding.ClusterSharding
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.{ForIdentifier, StorageInfo, StorageReferenceStored}
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.actor.segmentedstates.{GetSegmentedState, SaveSegmentedState, SegmentedStateStore, ValidationError}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.logging.LoggingUtil
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.engine.{BaseAsyncAccessImpl, ProtoRef, SegmentStoreAccess, StoredSegment}
import com.evernym.verity.protocol.engine.asyncService.AsyncOpRunner
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}
import com.evernym.verity.storage_services.aws_s3.StorageAPI
import com.typesafe.scalalogging.Logger
import scalapb.GeneratedMessage

import scala.concurrent.Future
import scala.util.Try

class SegmentStorageAccessAPI(val appConfig: AppConfig,
                              storageAPI: StorageAPI,
                              context: ActorContext,
                              protoRef: ProtoRef,
                              segmentedStateName: Option[String])
                             (implicit asyncAPIContext: AsyncAPIContext)
  extends SegmentStoreAccess
    with BaseAsyncAccessImpl
    with HasActorResponseTimeout {

  implicit val asyncOpRunner: AsyncOpRunner = asyncAPIContext.asyncOpRunner

  private val logger: Logger = LoggingUtil.getLoggerByClass(getClass)
  private val MAX_SEGMENT_SIZE = 400000   //TODO: shouldn't this be little less than 400 KB?

  private def isLessThanMaxSegmentSize(data: GeneratedMessage): Boolean = data.serializedSize < MAX_SEGMENT_SIZE

  private def sendToSegmentedRegion(segmentAddress: SegmentAddress, cmd: Any): Future[StoredSegment] = {
    val typeName = SegmentedStateStore.buildTypeName(protoRef, segmentedStateName.get)
    val segmentedStateRegion = ClusterSharding.get(context.system).shardRegion(typeName)
    val fut = segmentedStateRegion ? ForIdentifier(segmentAddress, cmd)
    fut map {
      case Some(v: Any)         => StoredSegment(segmentAddress, Option(v))
      case None                 => StoredSegment(segmentAddress, None)
      case ve: ValidationError  => throw new RuntimeException("segment validation error: " + ve.error)
    }
  }

  private def storeInExternalStorage(segmentAddress: SegmentAddress,
                                     segmentKey: SegmentKey,
                                     data: GeneratedMessage): Future[StoredSegment] = {
    logger.debug(s"storing storage state: $data")
    storageAPI.upload(segmentAddress + segmentKey, data.toByteArray).flatMap {
      case storageInfo: StorageInfo =>
        logger.debug(s"Data stored at: ${storageInfo.endpoint}")
        val storageReference = StorageReferenceStored(storageInfo.`type`, SegmentedStateStore.eventCode(data), Some(storageInfo))
        sendToSegmentedRegion(segmentAddress, SaveSegmentedState(segmentKey, storageReference))
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
                                 segment: Any): Future[StoredSegment] = {
    val fut = segment match {
      case segmentData: GeneratedMessage if isLessThanMaxSegmentSize(segmentData) =>
        val cmd = SaveSegmentedState(segmentKey, segmentData)
        sendToSegmentedRegion(segmentAddress, cmd)
      case segmentData: GeneratedMessage =>
        logger.debug(s"storing $segmentData in segment storage")
        storeInExternalStorage(segmentAddress, segmentKey, segmentData)
    }
    fut map {
      case ss @ StoredSegment(_, Some(_)) => ss.copy(segment = Option(segment))
      case other                          => other
    }
  }

  private def readFromExternalStorage[T](segmentAddress: SegmentAddress,
                                         segmentKey: SegmentKey,
                                         storageRef: StorageReferenceStored): Future[Option[T]] = {
    storageAPI.download(segmentAddress + segmentKey).map { data: Array[Byte]  =>
      Option(SegmentedStateStore.buildEvent(storageRef.eventCode, data).asInstanceOf[T])
    }
  }

  private def readSegmentedState(segmentAddress: SegmentAddress, segmentKey: SegmentKey): Future[Any] = {
    val cmd = GetSegmentedState(segmentKey)
    sendToSegmentedRegion(segmentAddress, cmd).flatMap {
      case StoredSegment(segmentAddress, Some(srs: StorageReferenceStored)) =>
        readFromExternalStorage(segmentAddress, segmentKey, srs)
      case StoredSegment(_, Some(segment: Any)) => Future.successful(Option(segment))
      case StoredSegment(_, None)               => Future.successful(None)
      case other  => throw new RuntimeException("unexpected response while retrieving segment: " + other)
    }
  }

  override def storeSegment(segmentAddress: SegmentAddress, segmentKey: SegmentKey, segment: Any)
                           (handler: Try[StoredSegment] => Unit): Unit = {
    withAsyncOpRunner({ saveSegmentedState(segmentAddress, segmentKey, segment) }, handler )
  }

  override def withSegment[T](segmentAddress: SegmentAddress, segmentKey: SegmentKey)
                             (handler: Try[Option[T]] => Unit): Unit = {
    withAsyncOpRunner({ readSegmentedState(segmentAddress, segmentKey) }, handler)
  }
}