package com.evernym.verity.cache.base

import java.util.UUID
import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.cache.fetchers.{AsyncCacheValueFetcher, CacheValueFetcher, SyncCacheValueFetcher}
import com.evernym.verity.cache.providers.{CacheProvider, CaffeineCacheParam, CaffeineCacheProvider}
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.observability.metrics.CustomMetrics._
import com.evernym.verity.observability.metrics.MetricsWriter
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

//TODO: as of now NOT using any distributed cache
// so in case of multi node cluster,
// there may/will be edge cases wherein same purpose cache exists on different nodes
// and they may give different values for same key for certain time (based on cache configured expiration time)

trait CacheBase extends HasExecutionContextProvider {

  private implicit def executionContext: ExecutionContext = futureExecutionContext

  protected val logger: Logger = getLoggerByClass(classOf[CacheBase])

  def name: String

  def fetchers: Map[FetcherParam, CacheValueFetcher]

  def metricsWriter: MetricsWriter

  //initializes/creates cache provider object for each registered fetchers
  private val cacheByFetcher: Map[FetcherParam, CacheProvider] =
    fetchers.map { case (param, fetcher) =>
      param -> createCacheProvider(fetcher)
    }

  //create cache provider object for given fetcher
  private def createCacheProvider(fetcher: CacheValueFetcher): CacheProvider = {
    //this is the only place where it knows about specific cache provider implementation
    // we are not using Caffeine "Loading" cache as that will require little bit of more effort
    // but we should keep doing some refactoring to move towards that direction

    val cacheParam =
      CaffeineCacheParam(
        fetcher.initialCapacity,
        fetcher.maxSize,
        fetcher.maxWeightParam,
        fetcher.expiryTimeInSeconds
      )
    new CaffeineCacheProvider(cacheParam)
  }

  def allCacheSize: Int = cacheByFetcher.values.map(_.size).sum
  def allCacheHitCount: Long = cacheByFetcher.values.map(_.hitCount).sum
  def allCacheMissCount: Long = cacheByFetcher.values.map(_.missCount).sum
  def allKeys: Set[String] = cacheByFetcher.values.flatMap(_.cachedObjects.keySet).toSet

  private def getCacheProvider(fetcherParam: FetcherParam): CacheProvider = {
    cacheByFetcher.getOrElse(fetcherParam, throw new RuntimeException("cache provider not found for fetcher: " + fetcherParam))
  }

  def getCachedObjectsByFetcherId(fetcherParam: FetcherParam): Map[CacheKey, Any] = {
    val cacheProvider = getCacheProvider(fetcherParam)
    cacheProvider.cachedObjects
  }

  private def addToFetcherCache(fetcherParam: FetcherParam, key: CacheKey, value: AnyRef): Unit = {
    val cacheProvider = getCacheProvider(fetcherParam)
    cacheProvider.put(key, value)
    logger.debug("cached object added: " + fetcherParam)
  }

  private def logMsg(fetcherParam: FetcherParam, reqId: String, msg: String): Unit = {
    logger.debug(s"[CACHE ($name:${fetcherParam.name})] crid: [$reqId] " +
      s"(tid: [${Thread.currentThread().getId}]) => msg = $msg")
  }

  private def logStats(fetcherParam: FetcherParam, reqId: String): Unit = {
    val cacheProvider = getCacheProvider(fetcherParam)
    logger.debug(s"[CACHE ($name:${fetcherParam.name})] crid: [$reqId] " +
      s"(tid: [${Thread.currentThread().getId}]) => cache stats = ${cacheProvider.stats}")
  }

  private def getFetcherById(fetcherParam: FetcherParam): CacheValueFetcher = {
    fetchers.getOrElse(fetcherParam, throw new RuntimeException("fetcher not found for fetcher: " + fetcherParam))
  }

  private def getAsyncFetcherById(fetcherParam: FetcherParam): AsyncCacheValueFetcher =
    getFetcherById(fetcherParam).asInstanceOf[AsyncCacheValueFetcher]

  private def getSyncFetcherById(fetcherParam: FetcherParam): SyncCacheValueFetcher =
    getFetcherById(fetcherParam).asInstanceOf[SyncCacheValueFetcher]

  private def collectMetrics(): Unit = {
    cacheByFetcher.foreach { case (fetcherParam, cacheProvider) =>
      val tags = Map("cache_name" -> name, "fetcher_id" -> fetcherParam.id.toString, "fetcher_name" -> fetcherParam.name)
      metricsWriter.gaugeUpdate(AS_CACHE_TOTAL_SIZE, allCacheSize, tags)
      metricsWriter.gaugeUpdate(AS_CACHE_HIT_COUNT, allCacheHitCount, tags)
      metricsWriter.gaugeUpdate(AS_CACHE_MISS_COUNT, allCacheMissCount, tags)
      metricsWriter.gaugeUpdate(AS_CACHE_SIZE, cacheProvider.size, tags)
    }
  }

  //this function gets executed when cache has to go to the source and fetch the actual value
  private def processFetchResultFromSource(fetchedResult: Map[CacheRequest, RespParam])
                                          (implicit frfc: FetchResultFromCache): Map[CacheRequest, AnyRef] = {
    logMsg(frfc.gcop.fetcherParam, frfc.reqId, "fetched data from source: " + fetchedResult)
    fetchedResult.foreach { case (cr, rp) =>
      if (frfc.fetcherExpiryTimeInSeconds.forall(_ > 0)) {
          addToFetcherCache(frfc.gcop.fetcherParam, cr.storageKey, rp.result)
      }
    }
    val found = frfc.objectsFoundInCache.map(fc => fc._1 -> fc._2)
    found ++ fetchedResult.map(r => r._1 -> r._2.result)
  }

  private def prepareFinalResponse(finalResult: Map[CacheRequest, AnyRef])
                                  (implicit frfc: FetchResultFromCache): QueryResult = {
    logStats(frfc.gcop.fetcherParam, frfc.reqId)
    collectMetrics()
    val fetcher = getFetcherById(frfc.gcop.fetcherParam)
    val requiredKeyNames = frfc.cacheRequests.filter(_.reqParam.required).map(_.storageKey)
    val missingKeys = frfc.cacheRequests.map(_.storageKey).diff(finalResult.keySet.map(_.storageKey))
    val requiredButMissing = missingKeys.intersect(requiredKeyNames)
    if (requiredButMissing.nonEmpty) {
      logMsg(frfc.gcop.fetcherParam, frfc.reqId, "given required keys neither found in cache nor in source: " +
        requiredButMissing)
      throw fetcher.throwRequiredKeysNotFoundException(requiredButMissing)
    } else {
      logMsg(frfc.gcop.fetcherParam, frfc.reqId, "returning requested keys: " + finalResult.keySet.mkString(", "))
      QueryResult(finalResult.map(r => r._1.respKey -> r._2))
    }
  }

  //fetch requested keys from cache only (this function doesn't go to the source)
  private def fetchFromCache(gcop: GetCachedObjectParam): FetchResultFromCache = {
    val id = UUID.randomUUID.toString
    logMsg(gcop.fetcherParam, id, "input param: " + gcop)
    val fetcher = getFetcherById(gcop.fetcherParam)
    val cacheRequests = gcop.reqParams.flatMap(fetcher.toCacheRequests)
    val requestedCacheKeys = cacheRequests.map(_.storageKey)
    val cacheProvider = getCacheProvider(gcop.fetcherParam)

    val cachedObjectsFound =
      cacheRequests
        .map(cr => cr -> cacheProvider.get(cr.storageKey))
        .filter(_._2.isDefined)
        .map(r => r._1 -> r._2.get)
        .toMap

    val keysFoundInCache = cachedObjectsFound.keySet.map(_.storageKey)
    val keysNotFoundInCache = requestedCacheKeys.diff(keysFoundInCache)
    val originalKeysNotFound = cacheRequests.filter(cr => keysNotFoundInCache.contains(cr.storageKey))
    logMsg(
      gcop.fetcherParam,
      id,
      msg = "keys found in cache : " +
        cacheRequests
          .filter(cr => keysFoundInCache.contains(cr.storageKey))
          .map(cr => cr.storageKey)
    )
    if (keysNotFoundInCache.nonEmpty)
      logMsg(
        gcop.fetcherParam,
        id,
        msg = "keys NOT found in cache : " +
          cacheRequests
            .filter(cr => keysNotFoundInCache.contains(cr.storageKey))
      )

    FetchResultFromCache(id, gcop, cacheRequests, fetcher.expiryTimeInSeconds, requestedCacheKeys,
      keysFoundInCache, keysNotFoundInCache, originalKeysNotFound, cachedObjectsFound)
  }

  def getByParamSync(gcop: GetCachedObjectParam): QueryResult = {
    val fetcher = getSyncFetcherById(gcop.fetcherParam)

    implicit val frfc: FetchResultFromCache = fetchFromCache(gcop)

    val finalResult = if (frfc.keysNotFoundInCache.nonEmpty) {
      processFetchResultFromSource(fetcher.getByRequests(frfc.objectsNotFoundInCache))
    } else {
      frfc.objectsFoundInCache.map(fc => fc._1 -> fc._2)
    }

    prepareFinalResponse(finalResult)
  }

  def getByParamAsync(gcop: GetCachedObjectParam): Future[QueryResult] = {

    val fetcher = getAsyncFetcherById(gcop.fetcherParam)

    implicit val cpfr: FetchResultFromCache = fetchFromCache(gcop)

    val finalResult = if (cpfr.keysNotFoundInCache.nonEmpty) {
      fetcher.getByRequests(cpfr.objectsNotFoundInCache).map { r =>
        processFetchResultFromSource(r)
      }.recover {
        case e: Exception => throw e
      }
    } else {
      Future(cpfr.objectsFoundInCache.map(fc => fc._1 -> fc._2))
    }

    finalResult map { fr =>
      prepareFinalResponse(fr)
    }
  }

}

/**
 *
 * @param data response data as "map of key value pair"
 */
case class QueryResult(data: Map[RespKey, Any]) extends CacheResponseUtil

class Cache(override val name: String,
            override val fetchers: Map[FetcherParam, CacheValueFetcher],
            override val metricsWriter: MetricsWriter,
            executionContext: ExecutionContext) extends CacheBase {
  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

/**
 *
 * @param cmd cache client provides the command to be used by cache fetcher
 * @param required is it required (either it should exists/provided by source or cache)
 */
case class ReqParam(cmd: Any, required: Boolean) {
  def cmdAs[T]: T = cmd.asInstanceOf[T]
}

/**
 *
 * @param result the fetched item from the source
 */
case class RespParam(result: AnyRef)

case class CacheRequest(reqParam: ReqParam, respKey: RespKey, storageKey: CacheKey)

object GetCachedObjectParam {

  def apply(kd: ReqParam, fetcherParam: FetcherParam): GetCachedObjectParam =
    GetCachedObjectParam(Set(kd), fetcherParam)
}
/**
 * input parameter to cache, multiple keys can be requested from cache
 *
 * @param reqParams set of key detail
 * @param fetcherParam fetcherParam
 */
case class GetCachedObjectParam(reqParams: Set[ReqParam], fetcherParam: FetcherParam)

case class FetchResultFromCache(reqId: String,
                                gcop: GetCachedObjectParam,
                                cacheRequests: Set[CacheRequest],
                                fetcherExpiryTimeInSeconds: Option[Int],
                                requestedCacheKeys: Set[CacheKey],
                                keysFoundInCache: Set[CacheKey],
                                keysNotFoundInCache: Set[CacheKey],
                                objectsNotFoundInCache: Set[CacheRequest],
                                objectsFoundInCache: Map[CacheRequest, AnyRef])

case class FetcherParam(id: Int, name: String)