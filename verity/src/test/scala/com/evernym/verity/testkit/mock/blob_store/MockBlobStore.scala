package com.evernym.verity.testkit.mock.blob_store

import akka.Done
import akka.actor.ActorSystem
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.config.AppConfig
import com.evernym.verity.storage_services.StorageAPI

import scala.concurrent.Future


class MockBlobStore(config: AppConfig)(implicit val as: ActorSystem) extends StorageAPI(config) {

  type BucketName = String
  type DBKey = String

  var bucketStore: Map[BucketName, Map[DBKey, Array[Byte]]] = Map.empty

  def calcKey(bucketName: String, id: String): String = s"$bucketName-$id"

  override def get(bucketName: String, id: String): Future[Array[Byte]] = {
    val dbKey = calcKey(bucketName, id)
    Future(bucketStore(bucketName)(dbKey))
  }

  override def put(bucketName: String, id: String, data: Array[Byte]): Future[StorageInfo] = {
    val dbKey = calcKey(bucketName, id)
    val bucketItems = bucketStore.getOrElse(bucketName, Map.empty) ++ Map(dbKey -> data)
    bucketStore += bucketName -> bucketItems
    Future(StorageInfo(dbKey, "mock"))
  }

  override def delete(bucketName: String, id: String): Future[Done] = {
    val dbKey = calcKey(bucketName, id)
    val bucketItems = bucketStore.getOrElse(bucketName, Map.empty) - dbKey
    bucketStore += (bucketName -> bucketItems)
    Future(Done)
  }

  def getBlobObjectCount(keyStartsWith: String, bucketName: BucketName): Int = {
    val dbKey = calcKey(bucketName, keyStartsWith)
    bucketStore.getOrElse(bucketName, Map.empty).count(_._1.startsWith(dbKey))
  }
}
