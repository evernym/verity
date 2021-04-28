package com.evernym.verity.storage_services.leveldb

import akka.actor.ActorSystem
import akka.Done
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.DATA_NOT_FOUND
import com.evernym.verity.actor.StorageInfo
import com.evernym.verity.config.AppConfig
import com.evernym.verity.storage_services.StorageAPI
import org.iq80.leveldb.impl.Iq80DBFactory

import scala.concurrent.Future
import org.iq80.leveldb.{DB, Options}

import java.io.File

/**
 * This is used for verity instances running in a local environment. It has not been tested for high load volume. The purpose
 * is to have storage for a contained environment.
 */
class LeveldbAPI(config: AppConfig)(implicit val as: ActorSystem) extends StorageAPI(config) {

  lazy val db: DB = {
    val path = config.config.getConfig("verity.blob-store").getString("local-store-path")
    val options = new Options()
      .createIfMissing(true)
      .paranoidChecks(true)
      .verifyChecksums(true)

    Iq80DBFactory.factory.open(new File(path), options)
  }

  private def dbKey(bucketName: String, id: String): String = s"$bucketName-$id"

  /**
   * @param id needs to be unique or data can be overwritten
   */
  def put(bucketName: String, id: String, data: Array[Byte]): Future[StorageInfo] = {
    db.put(dbKey(bucketName, id).getBytes(), data)
    Future(StorageInfo(dbKey(bucketName, id), "leveldb"))
  }

  def get(bucketName: String, id: String): Future[Array[Byte]] = {
      Option(db.get(dbKey(bucketName, id).getBytes())) match {
        case Some(x: Array[Byte]) => Future(x)
        case None => failure(DATA_NOT_FOUND.statusCode, s"No object for id: $id in bucket: $bucketName")
      }
  }

  def delete(bucketName: String, id: String): Future[Done] = {
    db.delete(dbKey(bucketName, id).getBytes())
    Future(Done)
  }

  def failure(code: String, msg: String): Future[Nothing] =
    Future.failed(new LeveldbFailure(code, Some(msg)))

  class LeveldbFailure(statusCode: String, statusMsg: Option[String] = None,
                  statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(statusCode, statusMsg, statusMsgDetail, errorDetail)
}
