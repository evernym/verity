package com.evernym.verity.storageAPI

import java.util.UUID
import akka.Done
import akka.actor.ActorSystem
import com.evernym.verity.actor.testkit.TestAppConfig
import com.evernym.verity.actor.testkit.actor.ActorSystemVanilla
import com.evernym.verity.config.AppConfig
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.storage_services.leveldb.LeveldbAPI
import com.evernym.verity.testkit.BasicAsyncSpec
import com.typesafe.config.{Config, ConfigValueFactory}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class LeveldbAPISpec extends BasicAsyncSpec with BeforeAndAfterAll{

  override protected def afterAll(): Unit = {
    super.afterAll()
    Await.result(system.terminate(), 10 seconds)
  }

  private def blobConfig(): Config = (new TestAppConfig)
    .config
    .withValue("verity.blob-store.storage-service", ConfigValueFactory.fromAnyRef("com.evernym.verity.storage_services.leveldb.LeveldbAPI"))
    .withValue("verity.blob-store.local-store-path", ConfigValueFactory.fromAnyRef(s"/tmp/verity/leveldb-spec-${UUID.randomUUID().toString}"))

  val appConfig: AppConfig = new TestAppConfig(Some(blobConfig()))
  lazy implicit val system: ActorSystem = ActorSystemVanilla("leveldb-test-system", appConfig.config)
  val BUCKET: String = "leveldb-bucket"

  val leveldbAPI: LeveldbAPI = StorageAPI.loadFromConfig(appConfig).asInstanceOf[LeveldbAPI]

  lazy val ID1: String = UUID.randomUUID.toString
  lazy val ID2: String = UUID.randomUUID.toString
  lazy val OBJ1: Array[Byte] = ID1.getBytes()
  lazy val OBJ2: Array[Byte] = Array.range(0, 700000).map(_.toByte)

  "LeveldbAPISpec" - {

    "when dealing with a small object" - {
      "should succeed in uploading" in {
        leveldbAPI put(BUCKET, ID1, OBJ1) map { _.endpoint shouldBe s"$BUCKET-$ID1" }
      }

      "should succeed downloading" in {
        leveldbAPI get(BUCKET, ID1) map { _ shouldBe OBJ1 }
      }

      "should be able to delete it" in {
        leveldbAPI delete(BUCKET, ID1) map { _ shouldBe Done }
      }

      "should fail to get the deleted object" in {
        val get = leveldbAPI.get(BUCKET, ID1)
        ScalaFutures.whenReady(get.failed) {e => e shouldBe a [leveldbAPI.LeveldbFailure]}
      }
    }

    "when dealing with a large object" - {
      "should succeed in uploading" in {
        leveldbAPI put(BUCKET, ID2, OBJ2) map { _.endpoint shouldBe s"$BUCKET-$ID2" }
      }

      "should succeed downloading" in {
        leveldbAPI get(BUCKET, ID2) map { _ shouldBe OBJ2 }
      }
    }
  }
}
