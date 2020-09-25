package com.evernym.integrationtests.e2e.third_party_apis.s3

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.alpakka.s3.BucketAccess.{AccessGranted, NotExists}
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.storage_services.aws_s3.S3AlpakkaApi
import com.evernym.verity.testkit.BasicAsyncSpec
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps


class AlpAkkaS3APISpec extends BasicAsyncSpec with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    Await.ready(alpAkkaS3API.createBucket(DEV_S3_BUCKET), 5 second)
  }

  val appConfig = new TestAppConfig
  lazy implicit val system: ActorSystem = ActorSystem("alp-akka-s3", appConfig.config)
  val DEV_S3_BUCKET: String = appConfig.config.getConfig("wallet.backup").getString("s3-bucket-name")

  val alpAkkaS3API = new S3AlpakkaApi(appConfig.config)

  lazy val ID1: String = UUID.randomUUID.toString
  lazy val ID2: String = UUID.randomUUID.toString
  lazy val OBJ1: Array[Byte] = ID1.getBytes()
  lazy val OBJ2: Array[Byte] = Array.range(0, 700000).map(_.toByte)

  "AlpAkkaS3API" - {

    "when asked to check if bucket exists" - {
      "should respond with NotExists" in {
        val fut = alpAkkaS3API.checkIfBucketExists(UUID.randomUUID.toString)
        fut map { r =>
          r shouldBe NotExists
        }
      }

      "should respond with AccessGranted" in {
        val fut = alpAkkaS3API.checkIfBucketExists(DEV_S3_BUCKET)
        fut map { r =>
          r shouldBe AccessGranted
        }
      }
    }

    "when dealing with a small object" - {
      "should succeed in uploading" in {
        val fut = alpAkkaS3API.upload(ID1, OBJ1)
        fut map { _.`type` shouldBe "S3" }
      }

      "should succeed downloading" in {
        val fut = alpAkkaS3API.download(ID1)
        fut map { r => r shouldBe OBJ1 }
      }
    }

    "when dealing with a large object" - {
      "should succeed in uploading" in {
        val fut = alpAkkaS3API.upload(ID2, OBJ2)
        fut map { _.`type` shouldBe "S3" }
      }

      "should succeed downloading" in {
        val fut = alpAkkaS3API.download(ID2)
        fut map { r =>
          r shouldBe OBJ2
        }
      }
    }

  }
}
