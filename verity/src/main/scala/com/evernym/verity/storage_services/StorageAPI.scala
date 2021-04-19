package com.evernym.verity.storage_services

import akka.Done
import akka.actor.ActorSystem
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.config.AppConfig

import scala.concurrent.Future

abstract class StorageAPI(val config: AppConfig) {
  def get(bucketName: String, id: String): Future[Array[Byte]]
  def put(bucketName: String, id: String, data: Array[Byte]): Future[StorageInfo]
  def delete(bucketName: String, id: String): Future[Done]
}

object StorageAPI {
  def loadFromConfig(appConfig: AppConfig)(implicit as: ActorSystem): StorageAPI = {
    Class
      .forName(appConfig.config.getConfig("verity.blob-store").getString("storage-service"))
      .getConstructor(classOf[AppConfig], classOf[ActorSystem])
      .newInstance(appConfig, as)
      .asInstanceOf[StorageAPI]
  }
}