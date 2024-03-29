package com.evernym.verity.storage_services.aws_s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, ContentTypes}
import akka.stream.Attributes
import akka.stream.alpakka.s3._
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.S3_FAILURE
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.config.AppConfig
import com.evernym.verity.storage_services.StorageAPI
import com.typesafe.config.{Config, ConfigFactory}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

//NOTE: if at all this file gets moved to different package, then it will require configuration change
// so until it is important, should avoid moving this to a different package.
class S3AlpakkaApi(appConfig: AppConfig,
                   executionContext: ExecutionContext,
                   overrideConfig: Config = ConfigFactory.empty())
                  (implicit val as: ActorSystem)
  extends StorageAPI(appConfig, executionContext, overrideConfig) {

  private implicit lazy val futureExecutionContext: ExecutionContext = executionContext

  def s3Settings: S3Settings = S3Settings(overrideConfig.withFallback(appConfig.config.getConfig("alpakka.s3")))
  lazy val s3Attrs: Attributes = S3Attributes.settings(s3Settings)

  def createBucket(bucketName: String): Future[Done] =  S3.makeBucket(bucketName)

  def checkIfBucketExists(bucketName: String): Future[BucketAccess] = S3 checkIfBucketExists bucketName

  /**
   * @param id needs to be unique or data can be overwritten
   */
  def put(bucketName: String,
          id: String,
          data: Array[Byte],
          contentType: ContentType = ContentTypes.`application/octet-stream`): Future[StorageInfo] = {
    val file: Source[ByteString, NotUsed] = Source.single(ByteString(data))

    val s3Sink: Sink[ByteString, Future[MultipartUploadResult]] =
      S3 multipartUpload(bucketName, id, contentType=contentType) withAttributes s3Attrs

    file.runWith(s3Sink).map(x => StorageInfo(x.location.toString(), "S3"))
  }

  def get(bucketName: String, id: String): Future[Option[Array[Byte]]] = {
    val s3File: Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
      S3 download(bucketName, id) withAttributes s3Attrs

    s3File.runWith(Sink.head) flatMap {
      case Some((data: Source[ByteString, _], _)) =>
        data.map(_.toByteBuffer.array())
          .runWith(Sink.seq)
          .map(d => Option(d.flatten.toArray))
      case None =>
        Future.successful(None)
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

  override def ping: Future[Unit] = {
    checkIfBucketExists("dummy-bucket-" + UUID.randomUUID()).map(_ => ())
  }
}
