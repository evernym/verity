package com.evernym.verity.cache.base

import java.util.UUID

import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.cache.fetchers.{AsyncCacheValueFetcher, CacheValueFetcher, SyncCacheValueFetcher}
import com.evernym.verity.cache.providers.{CacheProvider, CaffeineCacheParam, CaffeineCacheProvider}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.CustomMetrics._
import com.evernym.verity.metrics.MetricsWriter
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}

//TODO: as of now NOT using any distributed cache
// so in case of multi node cluster,
// there may/will be edge cases wherein same purpose cache exists on different nodes
// and they may give different values for same key for certain time (based on cache configured expiration time)

trait CacheBase extends HasExecutionContextProvider {

  private implicit def executionContext: ExecutionContext = futureExecutionContext

  type Key = String
  type FetcherId = Int

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

  def getCachedObjectsByFetcherId(fetcherParam: FetcherParam): Map[Key, Any] = {
    val cacheProvider = getCacheProvider(fetcherParam)
    cacheProvider.cachedObjects
  }

  private def addToFetcherCache(fetcherParam: FetcherParam, key: Key, value: AnyRef): Unit = {
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
  private def processFetchResultFromSource(fetchedResult: Map[String, AnyRef])
                                          (implicit frfc: FetchResultFromCache): Map[String, AnyRef] = {
    logMsg(frfc.gcop.fetcherParam, frfc.reqId, "fetched data from source: " + fetchedResult)
    fetchedResult.foreach { case (k, v) =>
      if (frfc.fetcherExpiryTimeInSeconds.forall(_ > 0)) {
        addToFetcherCache(frfc.gcop.fetcherParam, k, v)
      }
    }
    val found = frfc.cachedObjectsFound.map(fc => fc._1 -> fc._2)
    found ++ fetchedResult
  }

  private def prepareFinalResponse(finalResult: Map[String, AnyRef])
                                  (implicit frfc: FetchResultFromCache): CacheQueryResponse = {
    logStats(frfc.gcop.fetcherParam, frfc.reqId)
    collectMetrics()
    val fetcher = getFetcherById(frfc.gcop.fetcherParam)
    val requiredKeyNames = frfc.keyMappings.filter(_.keyDetail.required).map(_.loggingKey)
    val missingKeys = frfc.keyMappings.map(_.loggingKey).diff(finalResult.keySet)
    val requiredButMissing = missingKeys.intersect(requiredKeyNames)
    if (requiredButMissing.nonEmpty) {
      logMsg(frfc.gcop.fetcherParam, frfc.reqId, "given required keys neither found in cache nor in source: " +
        requiredButMissing)
      throw fetcher.throwRequiredKeysNotFoundException(requiredButMissing)
    } else {
      logMsg(frfc.gcop.fetcherParam, frfc.reqId, "returning requested keys: " + finalResult.keySet.mkString(", "))
      CacheQueryResponse(finalResult)
    }
  }

  //fetch requested keys from cache only (this function doesn't go to the source)
  private def fetchFromCache(gcop: GetCachedObjectParam): FetchResultFromCache = {
    val id = UUID.randomUUID.toString
    logMsg(gcop.fetcherParam, id, "input param: " + gcop)
    val fetcher = getFetcherById(gcop.fetcherParam)
    val keyMappings = fetcher.toKeyDetailMappings(gcop.kds)
    val requestedCacheKeys = keyMappings.map(_.cacheKey)
    val cacheProvider = getCacheProvider(gcop.fetcherParam)

    val cachedObjectsFound =
      requestedCacheKeys
        .map(k => k -> cacheProvider.get(k))
        .filter(_._2.isDefined)
        .map(r => r._1 -> r._2.get)
        .toMap

    val keysFoundInCache = cachedObjectsFound.keySet
    val keysNotFoundInCache = requestedCacheKeys.diff(keysFoundInCache)
    val originalKeysNotFound = keyMappings.filter(km => keysNotFoundInCache.contains(km.cacheKey)).map(_.keyDetail)
    logMsg(gcop.fetcherParam, id,
      "keys found in cache : " +
      keyMappings
        .filter(km => keysFoundInCache.contains(km.cacheKey))
        .map(k =>k.loggingKey)
    )
    if (keysNotFoundInCache.nonEmpty)
      logMsg(gcop.fetcherParam, id, "keys NOT found in cache : " + keyMappings.filter(km =>
        keysNotFoundInCache.contains(km.cacheKey)).map(_.loggingKey))

    FetchResultFromCache(gcop, id, keyMappings, fetcher.expiryTimeInSeconds, requestedCacheKeys,
      cachedObjectsFound, keysFoundInCache, keysNotFoundInCache, originalKeysNotFound)
  }

  def getByParamSync(gcop: GetCachedObjectParam): CacheQueryResponse = {
    val fetcher = getSyncFetcherById(gcop.fetcherParam)

    implicit val frfc: FetchResultFromCache = fetchFromCache(gcop)

    val finalResult = if (frfc.keysNotFoundInCache.nonEmpty) {
      processFetchResultFromSource(fetcher.getByKeyDetails(frfc.originalKeysNotFound))
    } else {
      frfc.cachedObjectsFound.map(fc => fc._1 -> fc._2)
    }

    prepareFinalResponse(finalResult)
  }

  def getByParamAsync(gcop: GetCachedObjectParam): Future[CacheQueryResponse] = {

    val fetcher = getAsyncFetcherById(gcop.fetcherParam)

    implicit val cpfr: FetchResultFromCache = fetchFromCache(gcop)

    val finalResult = if (cpfr.keysNotFoundInCache.nonEmpty) {
      fetcher.getByKeyDetails(cpfr.originalKeysNotFound).map { r =>
        processFetchResultFromSource(r)
      }.recover {
        case e: Exception => throw e
      }
    } else {
      Future(cpfr.cachedObjectsFound.map(fc => fc._1 -> fc._2))
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
case class CacheQueryResponse(data: Map[String, Any]) extends CacheResponseUtil

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
 * @param key key used by code for search/lookup purposes
 * @param required is it required (either it should exists/provided by source or cache)
 */
case class KeyDetail(key: Any, required: Boolean) {
  def keyAs[T]: T = key.asInstanceOf[T]
}

/**
 *
 * @param keyDetail key detail
 * @param cacheKey key used for cache value lookup
 * @param loggingKey key to be used for logging
 */
case class KeyMapping(keyDetail: KeyDetail, cacheKey: String, loggingKey: String)

object GetCachedObjectParam {

  def apply(kd: KeyDetail, fetcherParam: FetcherParam): GetCachedObjectParam =
    GetCachedObjectParam(Set(kd), fetcherParam)
}
/**
 * input parameter to cache, multiple keys can be requested from cache
 *
 * @param kds set of key detail
 * @param fetcherParam fetcherParam
 */
case class GetCachedObjectParam(kds: Set[KeyDetail], fetcherParam: FetcherParam)

case class FetchResultFromCache(gcop: GetCachedObjectParam,
                                reqId: String,
                                keyMappings: Set[KeyMapping],
                                fetcherExpiryTimeInSeconds: Option[Int],
                                requestedCacheKeys: Set[String],
                                cachedObjectsFound: Map[String, AnyRef],
                                keysFoundInCache: Set[String],
                                keysNotFoundInCache: Set[String],
                                originalKeysNotFound: Set[KeyDetail])

case class FetcherParam(id: Int, name: String)