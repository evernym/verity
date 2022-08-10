package com.evernym.verity.storage_services

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentType, ContentTypes}
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.config.AppConfig
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.{ExecutionContext, Future}

abstract class StorageAPI(val appConfig: AppConfig,
                          executionContext: ExecutionContext,
                          overrideConfig: Config = ConfigFactory.empty()) {
  def put(bucketName: String,
          id: String,
          data: Array[Byte],
          contentType: ContentType = ContentTypes.`application/octet-stream`): Future[StorageInfo]
  def get(bucketName: String, id: String): Future[Option[Array[Byte]]]
  def delete(bucketName: String, id: String): Future[Done]
  def ping: Future[Unit]

  def stop(): Future[Unit] = Future.successful(())
}

object StorageAPI {
  def loadFromConfig(appConfig: AppConfig,
                     executionContext: ExecutionContext,
                     overrideConfig: Config = ConfigFactory.empty())
                    (implicit as: ActorSystem): StorageAPI = {
    Class
      .forName(appConfig.config.getConfig("verity.blob-store").getString("storage-service"))
      .getConstructor(classOf[AppConfig], classOf[ExecutionContext], classOf[Config], classOf[ActorSystem])
      .newInstance(appConfig, executionContext, overrideConfig, as)
      .asInstanceOf[StorageAPI]
  }
}
