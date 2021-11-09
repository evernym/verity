package com.evernym.verity.testkit.mock.blob_store

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, ContentTypes}
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.config.AppConfig
import com.evernym.verity.storage_services.StorageAPI
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.{ExecutionContext, Future}


class MockBlobStore(config: AppConfig, ec: ExecutionContext, overrideConfig: Config = ConfigFactory.empty())
  extends StorageAPI(config, ec, overrideConfig) {

  type BucketName = String
  type DBKey = String

  var bucketStore: Map[BucketName, Map[DBKey, Array[Byte]]] = Map.empty

  def calcKey(bucketName: String, id: String): String = s"$bucketName-$id"

  override def get(bucketName: String, id: String): Future[Option[Array[Byte]]] = {
    val dbKey = calcKey(bucketName, id)
    Future.successful(bucketStore(bucketName).get(dbKey))
  }

  override def put(bucketName: String, id: String, data: Array[Byte], contentType: ContentType = ContentTypes.`application/octet-stream`): Future[StorageInfo] = {
    synchronized {
      val dbKey = calcKey(bucketName, id)
      val bucketItems = bucketStore.getOrElse(bucketName, Map.empty) ++ Map(dbKey -> data)
      bucketStore += bucketName -> bucketItems
      Future.successful(StorageInfo(dbKey, "mock"))
    }
  }

  override def delete(bucketName: String, id: String): Future[Done] = {
    synchronized {
      val dbKey = calcKey(bucketName, id)
      val bucketItems = bucketStore.getOrElse(bucketName, Map.empty) - dbKey
      bucketStore += (bucketName -> bucketItems)
      Future.successful(Done)
    }
  }

  def getBlobObjectCount(keyStartsWith: String, bucketName: BucketName): Int = {
    val dbKey = calcKey(bucketName, keyStartsWith)
    bucketStore.getOrElse(bucketName, Map.empty).count(_._1.startsWith(dbKey))
  }

  override def ping: Future[Unit] = Future.successful(())
}
