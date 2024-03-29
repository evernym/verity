package com.evernym.verity.cache.fetchers

import akka.util.Timeout
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, HandledErrorException, InternalServerErrorException}
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.util2.Status.{DATA_NOT_FOUND, StatusDetail, getUnhandledError}
import com.evernym.verity.cache.base.{CacheRequest, DEFAULT_MAX_CACHE_SIZE, FetcherParam, ReqParam, RespParam}
import com.evernym.verity.cache.providers.MaxWeightParam
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS
import com.evernym.verity.constants.Constants.DEFAULT_GENERAL_RESPONSE_TIMEOUT_IN_SECONDS
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByName
import com.evernym.verity.util.ObjectSizeUtil
import com.evernym.verity.util.Util.buildTimeout
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

trait CacheValueFetcher extends HasExecutionContextProvider {

  def appConfig: AppConfig

  val logger: Logger = getLoggerByName("CacheValueFetcher")
  def fetcherParam: FetcherParam

  //config path for the given cache fetcher
  def cacheConfigPath: Option[String]

  //cache fetcher implementation can override these default values
  def defaultInitialCapacity: Option[Int] = None                    //default initial capacity
  def defaultMaxSize: Option[Int] = Option(DEFAULT_MAX_CACHE_SIZE)  //default max cache size
  def defaultMaxWeightInBytes: Option[Long] = None                  //default max weight
  def defaultExpiryTimeInSeconds: Option[Int] = None                //default expiry time

  final val expiryTimeInSeconds: Option[Int] = cacheConfigPath.flatMap { ccp =>
    appConfig.getIntOption(s"$ccp.expiration-time-in-seconds")} orElse defaultExpiryTimeInSeconds
  final lazy val initialCapacity: Option[Int] = cacheConfigPath.flatMap { ccp =>
    appConfig.getIntOption(s"$ccp.initial-capacity") } orElse defaultInitialCapacity
  final lazy val maxWeightInBytes: Option[Long] = cacheConfigPath.flatMap { ccp =>
    appConfig.getBytesOption(s"$ccp.max-weight")}  orElse defaultMaxWeightInBytes
  final lazy val maxWeightParam: Option[MaxWeightParam] = maxWeightInBytes.map { mw =>
    MaxWeightParam(mw, weigher)
  }
  final lazy val maxSize: Option[Int] = {
    if (maxWeightInBytes.isDefined) None    //max weight takes priority
    else {
      cacheConfigPath.flatMap { ccp =>
        appConfig.getIntOption(s"$ccp.max-size")
      } orElse defaultMaxSize
    }
  }

  //this is an initial implementation but can be overridden by implementation class
  // and/or also can be changed in the future
  def weigher(key: String, value: AnyRef): Int = {
    val keySize = ObjectSizeUtil.calcSizeInBytes(key)
    val valueSize = ObjectSizeUtil.calcSizeInBytes(value)
    (keySize + valueSize).toInt
  }

  //NOTE: this provides mapping from key param to Cache Request
  def toCacheRequests(rp: ReqParam): Set[CacheRequest]

  def composeMultiKeyDetailResult(result: Set[(CacheRequest, Option[RespParam])]): Map[CacheRequest, RespParam] =
    result.filter(_._2.isDefined).map(r => r._1 -> r._2.get).toMap

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
  def getByRequest(cr: CacheRequest): Option[RespParam]

  def getByRequests(crs: Set[CacheRequest]): Map[CacheRequest, RespParam] = {
    val result = crs.map { cr => cr -> getByRequest(cr)}
    composeMultiKeyDetailResult(result)
  }
}

trait AsyncCacheValueFetcher extends CacheValueFetcher {
  private implicit def executionContext: ExecutionContext = futureExecutionContext

  implicit val timeout: Timeout = buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_RESPONSE_TIMEOUT_IN_SECONDS)

  def getByRequest(cr: CacheRequest): Future[Option[RespParam]]

  def getByRequests(crs: Set[CacheRequest]): Future[Map[CacheRequest, RespParam]] = {
    Future.traverse(crs) { cr =>
      getByRequest(cr).map(r => cr -> r)
    } flatMap { result =>
      Future.successful(composeMultiKeyDetailResult(result))
    }
  }
}

