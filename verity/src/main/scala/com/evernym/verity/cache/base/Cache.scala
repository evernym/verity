package com.evernym.verity.cache.base

import java.util.UUID

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.cache.fetchers.{AsyncCacheValueFetcher, CacheValueFetcher, SyncCacheValueFetcher}
import com.evernym.verity.cache.providers.{CacheProvider, CaffeineCacheParam, CaffeineCacheProvider}
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.metrics.MetricsWriter
import com.evernym.verity.metrics.CustomMetrics._
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future

//TODO: as of now NOT using any distributed cache
// so in case of multi node cluster,
// there may/will be edge cases wherein same purpose cache exists on different nodes
// and they may give different values for same key for certain time (based on cache configured expiration time)

trait CacheBase {

  type Key = String
  type FetcherId = Int

  val logger: Logger = getLoggerByClass(classOf[CacheBase])

  def name: String

  def fetchers: Map[FetcherId, CacheValueFetcher]

  //initializes/creates cache provider object for each registered fetchers
  private val cacheByFetcherId: Map[FetcherId, CacheProvider] =
    fetchers.map { case (fId, fetcher) =>
      fId -> createCacheProvider(fetcher)
    }

  //create cache provider object for given fetcher
  private def createCacheProvider(fetcher: CacheValueFetcher): CacheProvider = {
    val (maxWeightParam, maxSize) =
      (fetcher.maxWeightParam, fetcher.maxSize) match {
        case (Some(_),  None)     => (fetcher.maxWeightParam, None)
        case (None,     Some(_))  => (None, fetcher.maxSize)
        case (None,     None)     => (None, Option(DEFAULT_MAX_CACHE_SIZE))
        case (Some(_),  Some(_))  =>
          logger.info("cache max size and max weight both are set, max size will be used, fetcher id: " + fetcher.id)
          (None, fetcher.maxSize)
      }

    //this is the only place where it knows about specific cache provider implementation
    val cacheParam =
      CaffeineCacheParam(
        fetcher.initialCapacity,
        fetcher.expiryTimeInSeconds,
        maxSize,
        maxWeightParam
      )
    new CaffeineCacheProvider(cacheParam)
  }

  def allCacheSize: Int = cacheByFetcherId.values.map(_.size).sum
  def allCacheHitCount: Long = cacheByFetcherId.values.map(_.hitCount).sum
  def allCacheMissCount: Long = cacheByFetcherId.values.map(_.missCount).sum

  def getCacheProvider(fetcherId: Int): CacheProvider = {
    cacheByFetcherId.getOrElse(fetcherId, throw new RuntimeException("cache provider not found for fetcher id: " + fetcherId))
  }

  def getCachedObjectsByFetcherId(fetcherId: Int): Map[Key, Any] = {
    val cacheProvider = getCacheProvider(fetcherId)
    cacheProvider.cachedObjects
  }

  private def addToFetcherCache(fetcherId: FetcherId, key: Key, value: Any): Unit = {
    val cacheProvider = getCacheProvider(fetcherId)
    cacheProvider.put(key, value)
    logger.debug("cached object added: " + fetcherId)
  }

  private def logMsg(fetcherId: Int, reqId: String, msg: String): Unit = {
    logger.debug(s"[CACHE ($name:$fetcherId)] crid: [$reqId] " +
      s"(tid: [${Thread.currentThread().getId}]) => msg = $msg")
  }

  private def logStats(fetcherId: Int, reqId: String): Unit = {
    val cacheProvider = getCacheProvider(fetcherId)
    logger.debug(s"[CACHE ($name:$fetcherId)] crid: [$reqId] " +
      s"(tid: [${Thread.currentThread().getId}]) => cache stats = ${cacheProvider.stats}")
  }

  private def getFetcherById(id: Int): CacheValueFetcher = {
    fetchers.getOrElse(id, throw new RuntimeException("fetcher not found by id: " + id))
  }

  private def getAsyncFetcherById(id: Int): AsyncCacheValueFetcher =
    getFetcherById(id).asInstanceOf[AsyncCacheValueFetcher]

  private def getSyncFetcherById(id: Int): SyncCacheValueFetcher =
    getFetcherById(id).asInstanceOf[SyncCacheValueFetcher]

  //this function gets executed when cache has to go to the source and fetch the actual value
  private def processFetchedData(fetchedResult: Map[String, Any])
                                (implicit ipr: CachePreFetchResult): Map[String, Any] = {
    logMsg(ipr.gcop.fetcherId, ipr.reqId, "fetched data from source: " + fetchedResult)
    fetchedResult.foreach { case (k, v) =>
      val ettls = (ipr.gcop.expiryTimeInSeconds ++ ipr.fetcherExpiryTimeInSeconds).headOption
      if (ettls.forall(_ > 0)) {
        addToFetcherCache(ipr.gcop.fetcherId, k, v)
      }
    }
    val found = ipr.cachedObjectsFound.map(fc => fc._1 -> fc._2)
    found ++ fetchedResult
  }

  private def prepareFinalResponse(finalResult: Map[String, Any])
                                  (implicit cpfr: CachePreFetchResult): CacheQueryResponse = {
    logStats(cpfr.gcop.fetcherId, cpfr.reqId)
    collectMetrics()
    val fetcher = getFetcherById(cpfr.gcop.fetcherId)
    val requiredKeyNames = cpfr.keyMappings.filter(_.keyDetail.required).map(_.loggingKey)
    val missingKeys = cpfr.keyMappings.map(_.loggingKey).diff(finalResult.keySet)
    val requiredButMissing = missingKeys.intersect(requiredKeyNames)
    if (requiredButMissing.nonEmpty) {
      logMsg(cpfr.gcop.fetcherId, cpfr.reqId, "given required keys neither found in cache nor in source: " +
        requiredButMissing)
      throw fetcher.throwRequiredKeysNotFoundException(requiredButMissing)
    } else {
      logMsg(cpfr.gcop.fetcherId, cpfr.reqId, "returning requested keys: " + finalResult.keySet.mkString(", "))
      CacheQueryResponse(finalResult)
    }
  }

  private def collectMetrics(): Unit = {
    val cacheTag = "cache_name" -> name
    val tags = Map(cacheTag)
    MetricsWriter.gaugeApi.updateWithTags(AS_CACHE_TOTAL_SIZE, allCacheSize, tags)
    MetricsWriter.gaugeApi.updateWithTags(AS_CACHE_HIT_COUNT, allCacheHitCount, tags)
    MetricsWriter.gaugeApi.updateWithTags(AS_CACHE_MISS_COUNT, allCacheMissCount, tags)
    cacheByFetcherId.foreach { case (fetcherId, cacheProvider) =>
      MetricsWriter.gaugeApi.updateWithTags(AS_CACHE_SIZE, cacheProvider.size,
        Map(cacheTag, "fetcher_id" -> fetcherId.toString))
    }
  }

  private def getPreFetchResult()(implicit gcop: GetCachedObjectParam):
  CachePreFetchResult = {
    val id = UUID.randomUUID.toString
    logMsg(gcop.fetcherId, id, "input param: " + gcop)
    val fetcher = getFetcherById(gcop.fetcherId)
    val keyMappings = fetcher.toKeyDetailMappings(gcop.kds)
    val requestedCacheKeys = keyMappings.map(_.cacheKey)
    val cacheProvider = getCacheProvider(gcop.fetcherId)

    val cachedObjectsFound =
      requestedCacheKeys
        .map(k => k -> cacheProvider.get(k))
        .filter(_._2.isDefined)
        .map(r => r._1 -> r._2.get)
        .toMap

    val keysFoundInCache = cachedObjectsFound.keySet
    val keysNotFoundInCache = requestedCacheKeys.diff(keysFoundInCache)
    val originalKeysNotFound = keyMappings.filter(km => keysNotFoundInCache.contains(km.cacheKey)).map(_.keyDetail)
    logMsg(gcop.fetcherId, id,
      "keys found in cache : " +
      keyMappings
        .filter(km => keysFoundInCache.contains(km.cacheKey))
        .map(k =>k.loggingKey)
    )
    if (keysNotFoundInCache.nonEmpty)
      logMsg(gcop.fetcherId, id, "keys NOT found in cache : " + keyMappings.filter(km =>
        keysNotFoundInCache.contains(km.cacheKey)).map(_.loggingKey))

    CachePreFetchResult(gcop, id, keyMappings, fetcher.expiryTimeInSeconds, requestedCacheKeys,
      cachedObjectsFound, keysFoundInCache, keysNotFoundInCache, originalKeysNotFound)
  }

  def getByParamSync(implicit gcop: GetCachedObjectParam): CacheQueryResponse = {
    val fetcher = getSyncFetcherById(gcop.fetcherId)

    implicit val cpfr: CachePreFetchResult = getPreFetchResult()

    val finalResult = if (cpfr.keysNotFoundInCache.nonEmpty) {
      processFetchedData(fetcher.getByKeyDetails(cpfr.originalKeysNotFound))
    } else {
      cpfr.cachedObjectsFound.map(fc => fc._1 -> fc._2)
    }

    prepareFinalResponse(finalResult)
  }

  def getByParamAsync(implicit gcop: GetCachedObjectParam): Future[CacheQueryResponse] = {

    val fetcher = getAsyncFetcherById(gcop.fetcherId)

    implicit val cpfr: CachePreFetchResult = getPreFetchResult()

    val finalResult = if (cpfr.keysNotFoundInCache.nonEmpty) {
      fetcher.getByKeyDetails(cpfr.originalKeysNotFound).map { r =>
        processFetchedData(r)
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

class Cache(override val name: String, override val fetchers: Map[Int, CacheValueFetcher]) extends CacheBase {
  logger.info(s"Cache '$name' initializing")
  fetchers.foreach(f =>logger.info(s"Cache '$name' fetcher id = ${f._1}, max size = ${f._2.maxSize}"))
}

/**
 *
 * @param key key used by code for search/lookup purposes
 * @param required is it required (either it should exists/provided by source or cache)
 */
case class KeyDetail(key: Any, required: Boolean)

/**
 *
 * @param keyDetail key detail
 * @param cacheKey key used in the cache
 * @param loggingKey key to be used in logging
 */
case class KeyMapping(keyDetail: KeyDetail, cacheKey: String, loggingKey: String)


/**
 *
 * @param kds set of key detail
 * @param fetcherId fetcher id
 * @param expiryTimeInSeconds time to live in seconds
 * @param cacheOnlyIfValueFound if value found in the source, then only cache it
 */
case class GetCachedObjectParam(kds: Set[KeyDetail],
                                fetcherId: Int,
                                expiryTimeInSeconds: Option[Int] = None, //for now this is only used for testing purposes
                                cacheOnlyIfValueFound: Boolean = true)

case class CachePreFetchResult(gcop: GetCachedObjectParam,
                               reqId: String,
                               keyMappings: Set[KeyMapping],
                               fetcherExpiryTimeInSeconds: Option[Int],
                               requestedCacheKeys: Set[String],
                               cachedObjectsFound: Map[String, Any],
                               keysFoundInCache: Set[String],
                               keysNotFoundInCache: Set[String],
                               originalKeysNotFound: Set[KeyDetail])