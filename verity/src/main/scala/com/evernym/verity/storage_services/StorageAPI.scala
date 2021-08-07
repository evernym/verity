package com.evernym.verity.storage_services

import akka.Done
import akka.actor.ActorSystem
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.config.AppConfig

import scala.concurrent.{ExecutionContext, Future}

abstract class StorageAPI(val config: AppConfig, executionContext: ExecutionContext) {
  def get(bucketName: String, id: String): Future[Option[Array[Byte]]]
  def put(bucketName: String, id: String, data: Array[Byte]): Future[StorageInfo]
  def delete(bucketName: String, id: String): Future[Done]
}

object StorageAPI {
  def loadFromConfig(appConfig: AppConfig, executionContext: ExecutionContext)(implicit as: ActorSystem): StorageAPI = {
    Class
      .forName(appConfig.config.getConfig("verity.blob-store").getString("storage-service"))
      .getConstructor(classOf[AppConfig], classOf[ExecutionContext], classOf[ActorSystem])
      .newInstance(appConfig, executionContext, as)
      .asInstanceOf[StorageAPI]
  }
}

trait StorageException extends Exception
case class ServiceNotFound(msg: String) extends StorageException
case class ObjectExpired(msg: String) extends StorageException
case class ObjectNotFound(msg: String) extends StorageException
