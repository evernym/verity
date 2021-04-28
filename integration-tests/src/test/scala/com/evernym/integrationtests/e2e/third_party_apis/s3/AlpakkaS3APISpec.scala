package com.evernym.integrationtests.e2e.third_party_apis.s3

import java.util.UUID
import akka.Done
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.BucketAccess.{AccessGranted, NotExists}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.storage_services.aws_s3.S3AlpakkaApi
import com.evernym.verity.testkit.BasicAsyncSpec
import com.typesafe.scalalogging.Logger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


class AlpakkaS3APISpec extends BasicAsyncSpec with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    Await.ready(alpAkkaS3API.createBucket(DEV_S3_BUCKET), 5 second)
  }

  val logger: Logger = getLoggerByClass(classOf[AlpakkaS3APISpec])

  val appConfig = new TestAppConfig
  lazy implicit val system: ActorSystem = ActorSystem("alp-akka-s3", appConfig.config)
  val DEV_S3_BUCKET: String = appConfig.config.getConfig("verity.blob-store").getString("bucket-name")

  val alpAkkaS3API: S3AlpakkaApi = StorageAPI.loadFromConfig(appConfig).asInstanceOf[S3AlpakkaApi]

  lazy val ID1: String = UUID.randomUUID.toString
  lazy val ID2: String = UUID.randomUUID.toString
  lazy val OBJ1: Array[Byte] = ID1.getBytes()
  lazy val OBJ2: Array[Byte] = Array.range(0, 700000).map(_.toByte)

  "AlpAkkaS3API" - {

    "when asked to check if bucket exists" - {
      "should respond with NotExists" in {
        val s3Settings = alpAkkaS3API.s3Settings
        alpAkkaS3API checkIfBucketExists UUID.randomUUID.toString map { _ shouldBe NotExists }
      }

      "should respond with AccessGranted" in {
        alpAkkaS3API checkIfBucketExists DEV_S3_BUCKET map { _ shouldBe AccessGranted }
      }
    }

    "when dealing with a small object" - {
      "should succeed in uploading" in {
        alpAkkaS3API put(DEV_S3_BUCKET, ID1, OBJ1) map { _.`type` shouldBe "S3" }
      }

      "should succeed downloading" in {
        alpAkkaS3API get(DEV_S3_BUCKET, ID1) map { _ shouldBe OBJ1 }
      }


      "should succeed getting object meta" in {
        alpAkkaS3API getObjectMetadata(DEV_S3_BUCKET, ID1) map { _.isEmpty shouldBe false }
      }

      "should be able to delete it" in {
        alpAkkaS3API delete(DEV_S3_BUCKET, ID1) map { _ shouldBe Done }
      }

      "should fail to get the deleted object" in {
        val get = alpAkkaS3API get(DEV_S3_BUCKET, ID1)
        ScalaFutures.whenReady(get.failed) { _ shouldBe a [alpAkkaS3API.S3Failure]}
      }

      "should fail getting object meta" in {
        val get = alpAkkaS3API getObjectMetadata(DEV_S3_BUCKET, ID1)
        ScalaFutures.whenReady(get.failed) { _ shouldBe a [alpAkkaS3API.S3Failure]}
      }

    }

    "when dealing with a large object" - {
      "should succeed in uploading" in {
        alpAkkaS3API put(DEV_S3_BUCKET, ID2, OBJ2) map { _.`type` shouldBe "S3" }
      }

      "should succeed downloading" in {
        alpAkkaS3API get(DEV_S3_BUCKET, ID2) map { _ shouldBe OBJ2 }
      }
    }

    "when creating a new bucket" - {
      val newBucketName = s"new-bucket-$DEV_S3_BUCKET-2"
      val newId = UUID.randomUUID.toString

      "should succeed creating" in {
        alpAkkaS3API createBucket newBucketName map { _ shouldBe Done }
      }

      "should succeed in uploading" in {
        alpAkkaS3API put(newBucketName, newId, newId.getBytes()) map { _.`type` shouldBe "S3" }
      }

      "should do succeed in downloading" in {
        alpAkkaS3API get(newBucketName, newId) map { _ shouldBe newId.getBytes() }
      }
    }
  }
}
