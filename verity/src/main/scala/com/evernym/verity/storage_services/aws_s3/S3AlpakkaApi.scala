package com.evernym.verity.storage_services.aws_s3

import akka.actor.ActorSystem
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.Attributes
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.config.Config
import com.evernym.verity.Status.S3_FAILURE
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.actor.StorageInfo

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import scala.concurrent.Future

trait StorageAPI {
  def upload(id: String, data: Array[Byte]): Future[StorageInfo]
  def download(id: String): Future[Array[Byte]]
}

class S3AlpakkaApi(config: Config)(implicit val as: ActorSystem) extends StorageAPI {
  implicit val s3Attributes: Attributes = S3Attributes.settings(S3Settings(config.getConfig("alpakka.s3")))

  //TODO: the class name seems to be generic but the below bucket configuration seem to be tightly coupled with wallet bucket???
  val bucketName: String = config.getConfig("wallet.backup").getString("s3-bucket-name")

  def createBucket(bucketName: String): Future[Done] = {
    S3.makeBucket(bucketName)
  }

  def checkIfBucketExists(bucketName: String): Future[BucketAccess] = {
    S3.checkIfBucketExists(bucketName)
  }

  def upload(id: String, data: Array[Byte]): Future[StorageInfo] = {
    val file: Source[ByteString, NotUsed] = Source.single(ByteString(data))

    val s3Sink: Sink[ByteString, Future[MultipartUploadResult]] =
      S3 multipartUpload(bucketName, id) withAttributes s3Attributes

    file.runWith(s3Sink).map(x => StorageInfo(x.location.toString(), "S3"))
  }

  def download(id: String): Future[Array[Byte]] = {
    val s3File: Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
      S3 download(bucketName, id) withAttributes s3Attributes

    s3File.runWith(Sink.head) flatMap {
      case Some((data: Source[ByteString, _], _)) =>
        data.map(_.toByteBuffer.array())
          .runWith(Sink.seq)
          .map(_.flatten.toArray)
      case None => throw new S3Failure(S3_FAILURE.statusCode)
    }
  }

  class S3Failure(statusCode: String, statusMsg: Option[String] = None,
                  statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(statusCode, statusMsg, statusMsgDetail, errorDetail)
}
