package com.evernym.verity.storage_services.leveldb

import akka.actor.ActorSystem
import akka.Done
import akka.http.scaladsl.model.{ContentType, ContentTypes}
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.config.AppConfig
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.storage_services.StorageAPI
import com.evernym.verity.util2.Exceptions
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import org.iq80.leveldb.impl.Iq80DBFactory

import scala.concurrent.{ExecutionContext, Future}
import org.iq80.leveldb.{DB, Options}

import java.io.File
import scala.util.{Failure, Success, Try}

/**
 * This is used for verity instances running in a local environment. It has not been tested for high load volume.
 * The purpose is to have storage for a contained environment.
 */

//NOTE: if at all this file gets moved to different package, then it will require configuration change
// so until it is important, should avoid moving this to a different package.
class LeveldbAPI(appConfig: AppConfig,
                 executionContext: ExecutionContext,
                 overrideConfig: Config = ConfigFactory.empty())
                (implicit val as: ActorSystem)
  extends StorageAPI(appConfig, executionContext, overrideConfig) {

  private val logger: Logger = getLoggerByClass(getClass)

  private val path: String = overrideConfig
    .withFallback(appConfig.config.getConfig("verity.blob-store"))
    .getString("local-store-path")

  private val options: Options = new Options()
    .createIfMissing(true)
    .paranoidChecks(true)
    .verifyChecksums(true)

  private val db: DB = synchronized {
    Try(Iq80DBFactory.factory.open(new File(path), options)) match {
      case Success(db) =>
        logger.debug(s"successfully opened blob storage file '$path'")
        db
      case Failure(e) =>
        logger.error(s"error while opening blob storage file '$path': " + Exceptions.getStackTraceAsString(e))
        throw e
    }
  }

  private def withDB[T](bucketName: String, id: String)(f: (String, DB) => T): T = synchronized {
    val key = dbKey(bucketName, id)
    f(key, db)
  }

  private def dbKey(bucketName: String, id: String): String = s"$bucketName-$id"

  override def ping: Future[Unit] = Future.successful((): Unit)

  /**
   * @param id needs to be unique or data can be overwritten
   */
  def put(bucketName: String,
          id: String,
          data: Array[Byte],
          contentType: ContentType = ContentTypes.`application/octet-stream`): Future[StorageInfo] = {
    withDB(bucketName, id) { (key, db) =>
      db.put(key.getBytes(), data)
      Future.successful(StorageInfo(key))
    }
  }

  def get(bucketName: String, id: String): Future[Option[Array[Byte]]] = {
    withDB(bucketName, id) { (key, db) =>
      Future.successful(Option(db.get(key.getBytes())))
    }
  }

  def delete(bucketName: String, id: String): Future[Done] = {
    withDB(bucketName, id) { (key, db) =>
      db.delete(key.getBytes())
      Future.successful(Done)
    }
  }

  /**
   * in case of this leveldb storage api, when "test verity instance" has to restart during the spec execution,
   * it wasn't able to acquire lock because the previous acquired lock wasn't released
   * when the "test verity instance" was stopped (as part of restart process)
   * there is no api to release the lock only without deleting/destroying the whole db file
   * so below is just a hack to get rid of the lock issue without deleting/destroying the db file content.
   * @return
   */
  override def stop(): Future[Unit] = {
    new File(path + "/LOCK").delete()
    Future.successful(())
  }
}