package com.evernym.verity.protocol.engine.asyncapi.segmentstorage

import akka.Done
import akka.actor.ActorRef
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import com.evernym.verity.actor.persistence.DefaultPersistenceEncryption
import com.evernym.verity.actor.segmentedstates._
import com.evernym.verity.actor.{ForIdentifier, StorageInfo, StorageReferenceStored}
import com.evernym.verity.config.ConfigConstants.SALT_EVENT_ENCRYPTION
import com.evernym.verity.encryptor.PersistentDataEncryptor
import com.evernym.verity.observability.logs.LoggingUtil
import com.evernym.verity.protocol.container.actor.AsyncAPIContext
import com.evernym.verity.protocol.container.asyncapis.BaseAsyncOpExecutorImpl
import com.evernym.verity.protocol.engine.ProtoRef
import com.evernym.verity.protocol.engine.asyncapi.{AsyncOpRunner, BaseAccessController}
import com.evernym.verity.protocol.engine.segmentedstate.SegmentedStateTypes.{SegmentAddress, SegmentKey}
import com.evernym.verity.storage_services.{BucketLifeCycleUtil, StorageAPI}
import com.typesafe.scalalogging.Logger
import scalapb.GeneratedMessage

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class SegmentStoreAccessAdapter(storageAPI: StorageAPI,
                                protoRef: ProtoRef)
                               (implicit val asyncAPIContext: AsyncAPIContext,
                                   implicit val asyncOpRunner: AsyncOpRunner,
                                   implicit val ec: ExecutionContext)
  extends SegmentStoreAccess
    with BaseAccessController
    with BaseAsyncOpExecutorImpl {
  override def storeSegment(segmentAddress: SegmentAddress,
                            segmentKey: SegmentKey,
                            segment: Any,
                            retentionPolicy: Option[String]=None) (handler: Try[StoredSegment] => Unit): Unit =
    withFutureOpRunner(
      {saveSegmentedState(segmentAddress, segmentKey, segment, retentionPolicy)},
      handler
    )
  override def withSegment[T](segmentAddress: SegmentAddress,
                              segmentKey: SegmentKey,
                              retentionPolicy: Option[String]=None) (handler: Try[Option[T]] => Unit): Unit =
    withFutureOpRunner(
      {readSegmentedState(segmentAddress, segmentKey, retentionPolicy)},
      handler
    )

  override def removeSegment(segmentAddress: SegmentAddress,
                             segmentKey: SegmentKey,
                             retentionPolicy: Option[String]) (handler: Try[SegmentKey] => Unit): Unit = {
    withFutureOpRunner(
      {runDeleteSegmentState(segmentAddress, segmentKey, retentionPolicy)},
      handler
    )
  }


  private val logger: Logger = LoggingUtil.getLoggerByClass(getClass)
  private val MAX_SEGMENT_SIZE = 399999

  private def isGreaterThanMaxSegmentSize(data: GeneratedMessage): Boolean = !isLessThanMaxSegmentSize(data)

  private def isLessThanMaxSegmentSize(data: GeneratedMessage): Boolean = data.serializedSize <= MAX_SEGMENT_SIZE

  lazy val typeName: String = SegmentedStateStore.buildTypeName(protoRef)
  lazy val segmentedStateRegion: ActorRef = ClusterSharding.get(context.system).shardRegion(typeName)

  private def sendToSegmentedRegion(segmentAddress: SegmentAddress, cmd: Any)
                                   (implicit ec: ExecutionContext): Future[Any] = {
    segmentedStateRegion ? ForIdentifier(segmentAddress, cmd)
  }

  private def sendToSegmentedRegion(segmentAddress: SegmentAddress,
                                    segmentKey: SegmentKey,
                                    cmd: Any)
                                   (implicit ec: ExecutionContext): Future[StoredSegment] = {
    val fut = sendToSegmentedRegion(segmentAddress, cmd)
    fut map {
      case Some(v: Any)         => StoredSegment(segmentAddress, segmentKey, Option(v))
      case None                 => StoredSegment(segmentAddress, segmentKey, None)
      case ve: ValidationError  => throw new RuntimeException("segment validation error: " + ve.error)
    }
  }

  private def blobEncryptionSeed(id: String): String =
    DefaultPersistenceEncryption.getEventEncryptionKey(id, appConfig)

  private def encryptBlob(blob: Array[Byte], id: String): Array[Byte] =
    new PersistentDataEncryptor(appConfig.getStringReq(SALT_EVENT_ENCRYPTION)).encrypt(blob, blobEncryptionSeed(id))

  private def decryptBlob(encryptedBlob: Array[Byte], id: String): Array[Byte] =
    new PersistentDataEncryptor(appConfig.getStringReq(SALT_EVENT_ENCRYPTION)).decrypt(encryptedBlob, blobEncryptionSeed(id))

  private def blobStoreBucket: String = appConfig
    .config
    .getConfig("verity.blob-store")
    .getString("bucket-name")

  private def storeInBlobStore(blob: BlobSegment, data: GeneratedMessage)
                              (implicit ec: ExecutionContext): Future[StoredSegment] = {
    logger.debug(s"storing storage state: $data")
    storageAPI
      .put(blob.bucketName, blob.lifecycleAddress, encryptBlob(data.toByteArray, blob.segmentAddress))
      .flatMap {
        case storageInfo: StorageInfo =>
          logger.debug(s"data stored at: ${storageInfo.endpoint}")
          val eventCode = SegmentedStateStore.eventCode(data)
          val storageReference = StorageReferenceStored(eventCode, Some(storageInfo))
          sendToSegmentedRegion(blob.segmentAddress, blob.segmentKey, SaveSegmentedState(blob.segmentKey, storageReference))
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
        sendToSegmentedRegion(segmentAddress, segmentKey, cmd)
    }

    fut map {
      case ss @ StoredSegment(_, _, Some(_)) => ss.copy(segment = Option(segment))
      case other                          => other
    }
  }

  private def readFromBlobStore[T](blob: BlobSegment,
                                   storageRef: StorageReferenceStored)
                                  (implicit ec: ExecutionContext): Future[Option[T]] = {
    storageAPI.get(blob.bucketName, blob.lifecycleAddress).map { encryptedData: Option[Array[Byte]]  =>
      encryptedData.map { ed =>
        SegmentedStateStore
          .buildEvent(storageRef.eventCode, decryptBlob(ed, blob.segmentAddress))
          .asInstanceOf[T]
      }
    }
  }

  private def deleteFromBlobStore(blob: BlobSegment)
                                 (implicit ec: ExecutionContext): Future[Done] = {
    storageAPI.delete(blob.bucketName, blob.lifecycleAddress)
  }

  private def readSegmentedState[T](segmentAddress: SegmentAddress,
                                    segmentKey: SegmentKey,
                                    retentionPolicy: Option[String]
                                   ) (implicit ec: ExecutionContext): Future[Any] = {
    val cmd = GetSegmentedState(segmentKey)
    sendToSegmentedRegion(segmentAddress, segmentKey, cmd).flatMap {
      case StoredSegment(segmentAddress, segmentKey, Some(srs: StorageReferenceStored)) =>
        readFromBlobStore(BlobSegment(blobStoreBucket, segmentAddress, segmentKey, retentionPolicy), srs)
      case StoredSegment(_, _, Some(segment: Any)) => Future.successful(Option(segment))
      case StoredSegment(_, _, None)               => Future.successful(None)
      case other  => throw new RuntimeException("unexpected response while retrieving segment: " + other)
    }
  }

  private def runDeleteSegmentState[T](segmentAddress: SegmentAddress,
                                       segmentKey: SegmentKey,
                                       retentionPolicy: Option[String]=None
                                      ) (implicit ec: ExecutionContext): Future[SegmentKey] = {
    val cmd = GetSegmentedState(segmentKey)
    sendToSegmentedRegion(segmentAddress, cmd).flatMap {
      case Some(_: StorageReferenceStored)   => deleteFromBlobStore(BlobSegment(blobStoreBucket, segmentAddress, segmentKey, retentionPolicy))
      case Some(_: Any) | None               => Future.successful(Done)
      case other  => throw new RuntimeException("unexpected response while getting retrieving segment: " + other)
    }.flatMap { _ =>
      sendToSegmentedRegion(segmentAddress, DeleteSegmentedState(segmentKey))
    }.map { _ =>
      segmentKey
    }
  }

}


case class BlobSegment(bucketName: String,
                       segmentAddress: SegmentAddress,
                       segmentKey: SegmentKey,
                       lifecycle: Option[String]) {
  def lifecycleAddress: String = BucketLifeCycleUtil.lifeCycleAddress(lifecycle, segmentAddress)
}