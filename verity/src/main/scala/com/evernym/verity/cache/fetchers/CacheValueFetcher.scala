package com.evernym.verity.cache.fetchers

import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException, InternalServerErrorException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{DATA_NOT_FOUND, StatusDetail, getUnhandledError}
import com.evernym.verity.cache.base.{KeyDetail, KeyMapping}
import com.evernym.verity.cache.providers.MaxWeightParam
import com.evernym.verity.logging.LoggingUtil.getLoggerByName
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

trait CacheValueFetcher {

  val logger: Logger = getLoggerByName("CacheValueFetcher")
  def id: Int

  def initialCapacity: Option[Int] = None            //implementation can override initial capacity
  def expiryTimeInSeconds: Option[Int]               //expiry time (if not specified, it shouldn't expire)
  def maxSize: Option[Int]                           //max cache size

  def maxWeight: Option[Int] = None                  //max weight to be overridden by implementation if needed

  //max weight param can be overridden by implementation if needed
  def maxWeightParam: Option[MaxWeightParam] = maxWeight.map { mw =>
    MaxWeightParam(mw, weigher)
  }

  //this is an initial implementation but can be overridden by implementation class
  // and also can be changed in future
  def weigher(key: String, value: Any): Int = {
    (key + value.toString).length
  }

  //NOTE: this provides mapping from key detail to KeyMapping
  def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping]

  def composeMultiKeyDetailResult(result: Set[Map[String, Any]]): Map[String, Any] =
    result.flatten.map(e => e._1 -> e._2).toMap

  def throwRequiredKeysNotFoundException(reqKeysNotFound: Set[String]): HandledErrorException = {
    new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("required keys not found: " + reqKeysNotFound.mkString(", ")))
  }

  def buildUnexpectedResponse(response: Any): HandledErrorException = {
    response match {
      case sd: StatusDetail => new BadRequestErrorException(sd.statusCode, Option(sd.statusMsg))
      case x =>
        val sd = getUnhandledError(x)
        new InternalServerErrorException(sd.statusCode, Option(sd.statusMsg))
    }
  }
}


trait SyncCacheValueFetcher extends CacheValueFetcher {
  def getByKeyDetail(kd: KeyDetail): Map[String, Any]

  def getByKeyDetails(kds: Set[KeyDetail]): Map[String, Any] = {
    val result = kds.map { kd =>
      getByKeyDetail(kd)
    }
    composeMultiKeyDetailResult(result)
  }
}

trait AsyncCacheValueFetcher extends CacheValueFetcher {

  def getByKeyDetail(kd: KeyDetail): Future[Map[String, Any]]

  def getByKeyDetails(kds: Set[KeyDetail]): Future[Map[String, Any]] = {
    Future.traverse(kds) { kd =>
      getByKeyDetail(kd)
    } flatMap { result =>
      Future {
        composeMultiKeyDetailResult(result)
      }
    }
  }
}