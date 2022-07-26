package com.evernym.verity.storage_services.leveldb

import akka.actor.ActorSystem
import akka.Done
import akka.http.scaladsl.model.{ContentType, ContentTypes}
import com.evernym.verity.util2.Exceptions.BadRequestErrorException
import com.evernym.verity.util2.Status.DATA_NOT_FOUND
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

  val logger: Logger = getLoggerByClass(getClass)

  private implicit val futureExecutionContext: ExecutionContext = executionContext

  val path: String = overrideConfig
    .withFallback(appConfig.config.getConfig("verity.blob-store"))
    .getString("local-store-path")

  val options: Options = new Options()
    .createIfMissing(true)
    .paranoidChecks(true)
    .verifyChecksums(true)

  val db: DB = synchronized {
    Try(Iq80DBFactory.factory.open(new File(path), options)) match {
      case Success(db) =>
        logger.debug(s"successfully opened blob storage file '$path'")
        db
      case Failure(e) =>
        logger.error(s"error while opening blob storage file '$path': " + Exceptions.getStackTraceAsString(e))
        throw e
    }
  }

  def withDB[T](f: DB => T): T = synchronized {
    f(db)
  }

  private def dbKey(bucketName: String, id: String): String = s"$bucketName-$id"

  /**
   * @param id needs to be unique or data can be overwritten
   */
  def put(bucketName: String,
          id: String,
          data: Array[Byte],
          contentType: ContentType = ContentTypes.`application/octet-stream`): Future[StorageInfo] = {
    Future {
      withDB { db =>
        db.put(dbKey(bucketName, id).getBytes(), data)
        StorageInfo(dbKey(bucketName, id))
      }
    }
  }

  def get(bucketName: String, id: String): Future[Option[Array[Byte]]] = {
    withDB { db =>
      Option(db.get(dbKey(bucketName, id).getBytes())) match {
        case Some(x: Array[Byte]) => Future(Some(x))
        case None => failure(DATA_NOT_FOUND.statusCode, s"No object for id: $id in bucket: $bucketName")
      }
    }
  }

  def delete(bucketName: String, id: String): Future[Done] = {
    withDB { db =>
      db.delete(dbKey(bucketName, id).getBytes())
      Future(Done)
    }
  }

  private def failure(code: String, msg: String): Future[Nothing] =
    Future.failed(new LeveldbFailure(code, Some(msg)))

  class LeveldbFailure(statusCode: String,
                       statusMsg: Option[String] = None,
                       statusMsgDetail: Option[String] = None,
                       errorDetail: Option[Any] = None)
    extends BadRequestErrorException(statusCode, statusMsg, statusMsgDetail, errorDetail)

  override def ping: Future[Unit] = Future.successful((): Unit)
}