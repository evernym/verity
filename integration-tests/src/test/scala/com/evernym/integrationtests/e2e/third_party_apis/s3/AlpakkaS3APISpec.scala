package com.evernym.integrationtests.e2e.third_party_apis.s3

import akka.Done
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.BucketAccess.{AccessGranted, NotExists}
import com.evernym.integrationtests.e2e.util.TestExecutionContextProvider
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.storage_services.aws_s3.S3AlpakkaApi
import com.evernym.verity.testkit.BasicAsyncSpec
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


class AlpakkaS3APISpec
  extends BasicAsyncSpec
    with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    Await.ready(alpAkkaS3API.createBucket(DEV_S3_BUCKET), 5 second)
  }

  val logger: Logger = getLoggerByClass(classOf[AlpakkaS3APISpec])

  val testConfig: String =
    """
      |verity.blob-store {
      |  bucket-name = "blob-store"
      |  storage-service = "com.evernym.verity.storage_services.aws_s3.S3AlpakkaApi"
      |  local-store-path = ""
      |}
      |
      |alpakka.s3 {
      |
      |  buffer = "memory"
      |
      |  aws {
      |    credentials {
      |      provider = static
      |
      |      access-key-id = "accessKey1"
      |      access-key-id = ${?S3_ACCESS_KEY_ID}
      |
      |      secret-access-key = "verySecretKey1"
      |      secret-access-key = ${?S3_SECRET_KEY}
      |    }
      |
      |    region {
      |      provider = static
      |      default-region = "us-west-2"
      |    }
      |  }
      |
      |
      |  access-style = virtual
      |  endpoint-url = "http://{bucket}.localhost:8001"
      |  endpoint-url = ${?BLOB_S3_ENDPOINT}
      |
      |}
      |""".stripMargin

  val appConfig = new TestAppConfig(Some{
    ConfigFactory.parseString(testConfig).resolve()
  })
  lazy implicit val system: ActorSystem = ActorSystem("alp-akka-s3", appConfig.config)

  val DEV_S3_BUCKET: String = appConfig.config.getConfig("verity.blob-store").getString("bucket-name")

  val alpAkkaS3API: S3AlpakkaApi =
    StorageAPI.loadFromConfig(appConfig, TestExecutionContextProvider.ecp.futureExecutionContext).asInstanceOf[S3AlpakkaApi]

  lazy val ID1: String = UUID.randomUUID.toString
  lazy val ID2: String = UUID.randomUUID.toString
  lazy val OBJ1: Array[Byte] = ID1.getBytes()
  lazy val OBJ2: Array[Byte] = Array.range(0, 700000).map(_.toByte)

  "AlpAkkaS3API" - {

    "when asked to check if bucket exists" - {
      "should respond with NotExists" in {
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
        alpAkkaS3API get(DEV_S3_BUCKET, ID1) map { data => checkArrayEquality(Option(OBJ1), data) }
      }


      "should succeed getting object meta" in {
        alpAkkaS3API getObjectMetadata(DEV_S3_BUCKET, ID1) map { _.isEmpty shouldBe false }
      }

      "should be able to delete it" in {
        alpAkkaS3API delete(DEV_S3_BUCKET, ID1) map { _ shouldBe Done }
      }

      "should return None for deleted object" in {
        alpAkkaS3API get(DEV_S3_BUCKET, ID1) map { _ shouldBe None }
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
        alpAkkaS3API get(DEV_S3_BUCKET, ID2) map { data => checkArrayEquality(Option(OBJ2), data) }
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
        alpAkkaS3API get(newBucketName, newId) map { data => checkArrayEquality(Option(newId.getBytes), data)}
      }

      "when override config is present" - {
        "it should take precedence" in {
          val overrideConfig: Config = ConfigFactory.parseString(
            """
              |aws {
              |  region {
              |    default-region = "eu-central-1"
              |  }
              |}
              |""".stripMargin
          )

          val testAlpAkkaS3API: S3AlpakkaApi = StorageAPI.loadFromConfig(
            appConfig,
            TestExecutionContextProvider.ecp.futureExecutionContext,
            overrideConfig = overrideConfig
          ).asInstanceOf[S3AlpakkaApi]

          // should be the region from override config and not from main config.
          testAlpAkkaS3API.s3Settings.s3RegionProvider.getRegion.toString shouldBe "eu-central-1"
        }
      }
    }
  }
}
