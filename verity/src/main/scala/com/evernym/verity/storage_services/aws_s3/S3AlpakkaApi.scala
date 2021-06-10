package com.evernym.verity.storage_services.aws_s3

import akka.actor.ActorSystem
import akka.stream.Attributes
import akka.stream.alpakka.s3._
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.S3_FAILURE
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.config.AppConfig
import com.evernym.verity.storage_services.StorageAPI

import scala.concurrent.Future
import scala.language.postfixOps

//NOTE: if at all this file gets moved to different package, then it will require configuration change
// so until it is important, should avoid moving this to different package.

class S3AlpakkaApi(config: AppConfig)(implicit val as: ActorSystem) extends StorageAPI(config) {

  def s3Settings: S3Settings = S3Settings(config.config.getConfig("alpakka.s3"))
  lazy val s3Attrs: Attributes = S3Attributes.settings(s3Settings)

  def createBucket(bucketName: String): Future[Done] =  S3.makeBucket(bucketName)

  def checkIfBucketExists(bucketName: String): Future[BucketAccess] = S3 checkIfBucketExists bucketName

  /**
   * @param id needs to be unique or data can be overwritten
   */
  def put(bucketName: String, id: String, data: Array[Byte]): Future[StorageInfo] = {
    val file: Source[ByteString, NotUsed] = Source.single(ByteString(data))

    val s3Sink: Sink[ByteString, Future[MultipartUploadResult]] =
      S3 multipartUpload(bucketName, id) withAttributes s3Attrs

    file.runWith(s3Sink).map(x => StorageInfo(x.location.toString(), "S3"))
  }

  def get(bucketName: String, id: String): Future[Array[Byte]] = {
    val s3File: Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
      S3 download(bucketName, id) withAttributes s3Attrs

    s3File.runWith(Sink.head) flatMap {
      case Some((data: Source[ByteString, _], _)) =>
        data.map(_.toByteBuffer.array())
          .runWith(Sink.seq)
          .map(_.flatten.toArray)
      case None => throw new S3Failure(S3_FAILURE.statusCode, Some(s"No object for id: $id in bucket: $bucketName"))
    }

  }

  def getObjectMetadata(bucketName: String, id: String): Future[Map[String, String]] = {
    val s3Meta: Source[Option[ObjectMetadata], NotUsed] =
      S3 getObjectMetadata(bucketName, id) withAttributes s3Attrs

    s3Meta.runWith(Sink.head) flatMap {
      case Some(data: ObjectMetadata) =>
        Future(data.metadata.map {x => x.name() -> x.value()} toMap)
      case None => throw new S3Failure(S3_FAILURE.statusCode, Some(s"No object for id: $id in bucket: $bucketName"))
    }
  }

  def delete(bucketName: String, id: String): Future[Done] = {
    S3 deleteObject(bucketName, id) withAttributes s3Attrs runWith Sink.head flatMap {
      case x: Done => Future(x)
      case _ => throw new S3Failure(S3_FAILURE.statusCode, Some(s"Failed deleting object for id: $id in bucket: $bucketName"))
    }
  }

  class S3Failure(statusCode: String, statusMsg: Option[String] = None,
                  statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(statusCode, statusMsg, statusMsgDetail, errorDetail)
}
