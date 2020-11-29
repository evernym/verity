package com.evernym.verity.cache

import java.time.{LocalDateTime, ZonedDateTime}
import java.time.temporal.ChronoUnit
import java.util.UUID

import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions._
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{getUnhandledError, _}
import com.evernym.verity.actor.agent.agency.AgencyInfo
import com.evernym.verity.actor.agent.msgrouter.ActorAddressDetail
import com.evernym.verity.actor.agent.user.AgentConfigs
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.logging.LoggingUtil.{getLoggerByClass, getLoggerByName}
import com.evernym.verity.protocol.engine.DID
import com.typesafe.scalalogging.Logger

import scala.collection.mutable
import scala.concurrent.Future


/**
 * cached object
 *
 * @param value cached value
 * @param createdDateTime time when it was created/stored in cache
 * @param ttls time to live in seconds
 */
case class CachedObject(value: Option[Any],
                        createdDateTime: ZonedDateTime,
                        ttls: Option[Int] = None) {

  def isExpired: Boolean = {
    ttls.exists { seconds =>
      val curTime = ZonedDateTime.now()
      val expiryTime = createdDateTime.plusSeconds(seconds)
      expiryTime.isBefore(curTime)
    }
  }

  def getExpirationDetail: String = {
    ttls.map { seconds =>
      val curTime = ZonedDateTime.now()
      val expiryTime = createdDateTime.plusSeconds(seconds)
      if (expiryTime.isAfter(curTime)) {
        s"${ChronoUnit.SECONDS.between(curTime, expiryTime)} seconds remaining before expiration"
      } else {
        "expired"
      }
    }.getOrElse("no expiration time")
  }

  override def toString: String = {
    s"createdDateTime: $createdDateTime, ttls: $ttls"
  }
}

/**
 *
 * @param kds set of key detail
 * @param fetcherId fetcher id
 * @param ttls time to live in seconds
 * @param cacheOnlyIfValueFound if value found in the source, then only cache it
 */
case class GetCachedObjectParam(kds: Set[KeyDetail],
                                fetcherId: Int,
                                ttls: Option[Int] = None,
                                cacheOnlyIfValueFound: Boolean = true)

case class CachePreFetchResult(gcop: GetCachedObjectParam,
                               reqId: String,
                               keyMappings: Set[KeyMapping],
                               fetcherTtls: Option[Int],
                               requestedCacheKeys: Set[String],
                               cachedObjectsFound: Map[String, CachedObject],
                               keysFoundInCache: Set[String],
                               keysNotFoundInCache: Set[String],
                               originalKeysNotFound: Set[KeyDetail])

trait CacheBase {
  //TODO: as of now NOT using any distributed cache
  // so in case of multi node cluster,
  // there will be edge cases wherein same purpose cache exists on different nodes
  // and they may give different values for same key for certain time (based on cache expiration time you configured)

  type Key = String
  type FetcherId = Int

  val logger: Logger = getLoggerByClass(classOf[CacheBase])

  def name: String

  def fetchers: Map[Int, CacheValueFetcher]

  private val cachedObjects: mutable.HashMap[FetcherId, Map[Key, CachedObject]] = mutable.HashMap.empty
  private var fetcherCacheItemCleanup: Map[FetcherId, LocalDateTime] = Map.empty

  private var hit: Int = 0
  private var miss: Int = 0

  def size: Int = cachedObjects.values.flatten.size

  def getUnexpiredCachedObjectsByFetcherId(fetcherId: Int): Map[Key, CachedObject] = {
    val cachedObjectByFetcherId = cachedObjects.getOrElse(fetcherId, Map.empty)
    cachedObjectByFetcherId.filter(co => co._2.value.isDefined && ! co._2.isExpired)
  }

  /**
   * shall remove expired items and/or other items (LRU etc) if cache size is exceeded
   * @param fetcherId
   */
  private def performCacheCleanup(fetcherId: FetcherId): Unit = {
    removeIfMaxSizeExceeded(fetcherId)
    //removeExpired(fetcherId)    //enable once sure this won't cause any performance issue
  }

  /**
   * remove expired cached items for given fetcher id
   * @param fetcherId
   */
  private def removeExpired(fetcherId: FetcherId): Unit = {
    val CHECK_EVERY_N_MINUTES = 1
    val CHECK_TOP_N_ITEMS = 10

    val lastCheckedAt = fetcherCacheItemCleanup.get(fetcherId)
    val curTime = LocalDateTime.now()
    val timeInMinutesSinceLastChecked = lastCheckedAt.map(st => ChronoUnit.MINUTES.between(st, curTime))

    if (lastCheckedAt.isEmpty || timeInMinutesSinceLastChecked.exists(_> CHECK_EVERY_N_MINUTES)) {
      var cachedObjectsByFetcherId = cachedObjects.getOrElse(fetcherId, Map.empty)
      //we don't want to loop through all items as that will be inefficient (time taking)
      //so, just checking top n items and removing any expired ones
      val candidatesForExpirationCheck = cachedObjectsByFetcherId.take(CHECK_TOP_N_ITEMS)
      candidatesForExpirationCheck.foreach { case (key, co) =>
        if (co.isExpired) {
          cachedObjectsByFetcherId = cachedObjectsByFetcherId - key
        }
      }
      cachedObjects.put(fetcherId, cachedObjectsByFetcherId)
      fetcherCacheItemCleanup = fetcherCacheItemCleanup + (fetcherId -> curTime)
    }
  }

  /**
   * remove any cached items exceeded max size for the given fetcher id
   * @param fetcherId
   */
  private def removeIfMaxSizeExceeded(fetcherId: FetcherId): Unit = {
    val fetcher = fetchers(fetcherId)
    val cachedObjectsByFetcherId = cachedObjects.getOrElse(fetcherId, Map.empty)
    if (cachedObjectsByFetcherId.size > fetcher.maxSize) {
      val updatedCachedObjects = cachedObjectsByFetcherId.take(fetcher.maxSize)
      cachedObjects.put(fetcherId, updatedCachedObjects)
    }
  }

  private def addToCachedObject(fetcherId: FetcherId, key: Key, co: CachedObject): Unit = {
    logger.debug("cached object added: " + co)
    val cachedObjectByFetcherId = cachedObjects.getOrElse(fetcherId, Map.empty)
    val updatedCacheForFetcherId = cachedObjectByFetcherId + (key -> co)
    cachedObjects.put(fetcherId, updatedCacheForFetcherId)
    performCacheCleanup(fetcherId)
  }

  def deleteFromCache(fetcherId: FetcherId, keys: Set[Key]): Unit = {
    val cachedObjectByFetcherId = cachedObjects.getOrElse(fetcherId, Map.empty)
    val updatedCache = cachedObjectByFetcherId -- keys
    cachedObjects.put(fetcherId, updatedCache)
  }

  private def getCacheHitRatio: String = {
    val total = hit + miss
    if (total > 0) {
      val per = hit*100.0f/total
      f"$per%1.2f" + "%"
    } else "n/a"
  }

  private def logMsg(fetcherId: Int, reqId: String, msg: String): Unit = {
    logger.debug(s"[CACHE ($name:$fetcherId)] crid: [$reqId] " +
      s"(tid: [${Thread.currentThread().getId}]) => msg = $msg")
  }

  private def logHitRatio(fetcherId: Int, reqId: String): Unit = {
    logger.debug(s"[CACHE ($name:$fetcherId)] crid: [$reqId] " +
      s"(tid: [${Thread.currentThread().getId}]) => hit ratio = $getCacheHitRatio")
  }

  private def getFetcherById(id: Int): CacheValueFetcher = {
    fetchers.getOrElse(id, throw new RuntimeException("fetcher not found by id: " + id))
  }

  private def getAsyncFetcherById(id: Int): AsyncCacheValueFetcher =
    getFetcherById(id).asInstanceOf[AsyncCacheValueFetcher]

  private def getSyncFetcherById(id: Int): SyncCacheValueFetcher =
    getFetcherById(id).asInstanceOf[SyncCacheValueFetcher]

  private def processFetchedData(fetchedResult: Map[String, Any])
                        (implicit ipr: CachePreFetchResult): Map[String, Any] = {
    logMsg(ipr.gcop.fetcherId, ipr.reqId, "fetched data from source: " + fetchedResult)
    fetchedResult.foreach { case (k, v) =>
      val ettls = (ipr.gcop.ttls ++ ipr.fetcherTtls).headOption
      if (ettls.forall(_ > 0))
        addToCachedObject(ipr.gcop.fetcherId, k, CachedObject(Option(v), ZonedDateTime.now(), ettls))
    }
    val found = ipr.cachedObjectsFound.map(fc => fc._1 -> fc._2.value.orNull)
    found ++ fetchedResult
  }

  private def prepareFinalResponse(finalResult: Map[String, Any])
                                  (implicit cpfr: CachePreFetchResult): CacheQueryResponse = {
    logHitRatio(cpfr.gcop.fetcherId, cpfr.reqId)
    val fetcher = getFetcherById(cpfr.gcop.fetcherId)
    val requiredKeyNames = cpfr.keyMappings.filter(_.kd.required).map(_.outputKey)
    val missingKeys = cpfr.keyMappings.map(_.outputKey).diff(finalResult.keySet)
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

  private def getPreFetchResult()(implicit gcop: GetCachedObjectParam):
  CachePreFetchResult = {
    val id = UUID.randomUUID.toString
    logMsg(gcop.fetcherId, id, "input param: " + gcop)
    val fetcher = getFetcherById(gcop.fetcherId)
    val keyMappings = fetcher.getKeyDetailMapping(gcop.kds)
    val requestedCacheKeys = keyMappings.map(_.cacheKey)
    val cachedObjectByFetcherId = cachedObjects.getOrElse(gcop.fetcherId, Map.empty)

    val cachedObjectsFound =
      requestedCacheKeys
        .map(k => k -> cachedObjectByFetcherId.get(k))
        .filter(r => r._2.isDefined && ! r._2.exists(_.isExpired))
        .map(r => r._1 -> r._2.get)
        .toMap

    val keysFoundInCache = cachedObjectsFound.keySet
    val keysNotFoundInCache = requestedCacheKeys.diff(keysFoundInCache)
    val originalKeysNotFound = keyMappings.filter(km => keysNotFoundInCache.contains(km.cacheKey)).map(_.kd)
    logMsg(gcop.fetcherId, id, "keys found in cache : " + keyMappings.filter(km =>
      keysFoundInCache.contains(km.cacheKey)).map { k =>
        k.outputKey + s"${cachedObjectsFound.find(r => r._1 == k.cacheKey).map(k =>
          s" (${k._2.getExpirationDetail})").getOrElse("")}"
      }
    )
    if (keysNotFoundInCache.nonEmpty)
      logMsg(gcop.fetcherId, id, "keys NOT found in cache : " + keyMappings.filter(km =>
        keysNotFoundInCache.contains(km.cacheKey)).map(_.outputKey))
    hit += keysFoundInCache.size
    miss += keysNotFoundInCache.size

    CachePreFetchResult(gcop, id, keyMappings, fetcher.ttls, requestedCacheKeys,
      cachedObjectsFound, keysFoundInCache, keysNotFoundInCache, originalKeysNotFound)
  }

  def getByParamSync(implicit gcop: GetCachedObjectParam): CacheQueryResponse = {
    val fetcher = getSyncFetcherById(gcop.fetcherId)

    implicit val cpfr: CachePreFetchResult = getPreFetchResult()

    val finalResult = if (cpfr.keysNotFoundInCache.nonEmpty) {
      processFetchedData(fetcher.getByKeyDetails(cpfr.originalKeysNotFound))
    } else {
      cpfr.cachedObjectsFound.map(fc => fc._1 -> fc._2.value.orNull)
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
      Future(cpfr.cachedObjectsFound.map(fc => fc._1 -> fc._2.value.orNull))
    }

    finalResult map { fr =>
      prepareFinalResponse(fr)
    }
  }


}

trait CacheResponseUtil {

  def data: Map[String, Any]

  def get(key: String): Option[Any] = data.get(key)

  def getStringOpt(key: String): Option[String] = get(key).map(_.toString)

  def getStringReq(key: String): String = getStringOpt(key).getOrElse (
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("value not found for given key" + key))
  )

  def getConfigs: AgentConfigs = AgentConfigs(data.map(e => ConfigDetail(e._1, e._2.toString)).toSet)

  def getAgencyInfoOpt(forDID: DID): Option[AgencyInfo] = {
    data.get(forDID) match {
      case Some(ad: AgencyInfo) => Some(ad)
      case None => None
      case x => throw new InternalServerErrorException(UNHANDLED.statusCode, Option("unhandled error" + x))
    }
  }

  def getAgencyInfoReq(forDID: DID): AgencyInfo = {
    getAgencyInfoOpt(forDID).getOrElse(
      throw new InternalServerErrorException(UNHANDLED.statusCode, Option("agency info not found DID: " + forDID)))
  }

  def getActorAddressDetailOpt(forDID: DID): Option[ActorAddressDetail] = {
    data.get(forDID) match {
      case Some(aad: ActorAddressDetail) => Some(aad)
      case None => None
      case x => throw new InternalServerErrorException(UNHANDLED.statusCode, Option("unhandled error" + x))
    }
  }

  def getAgencyDIDOpt: Option[DID] = {
    data.get(AGENCY_DID_KEY) match {
      case Some(ad: String) => Some(ad)
      case None => None
      case x => throw new InternalServerErrorException(UNHANDLED.statusCode, Option("unhandled error" + x))
    }
  }

  def getAgencyDIDReq: String = {
    getAgencyDIDOpt match {
      case Some(ad: String) => ad
      case None => throw new NotFoundErrorException(AGENT_NOT_YET_CREATED.statusCode, Option(AGENT_NOT_YET_CREATED.statusMsg))
    }
  }

}

/**
 *
 * @param data response data as "map of key value pair"
 */
case class CacheQueryResponse(data: Map[String, Any]) extends CacheResponseUtil

class Cache(override val name: String, override val fetchers: Map[Int, CacheValueFetcher]) extends CacheBase

case class KeyDetail(key: Any, required: Boolean)

case class KeyMapping(kd: KeyDetail, cacheKey: String, outputKey: String)


trait CacheValueFetcher {

  val logger: Logger = getLoggerByName("CacheValueFetcher")

  def id: Int
  def maxSize: Int = 10     //each fetcher will have a max cached item size
  def ttls: Option[Int]

  //NOTE: this provides mapping between key detail, key to be used in cache and key to be used for display purposes
  def getKeyDetailMapping(kws: Set[KeyDetail]): Set[KeyMapping]

  def composeMultiKeyDetailResult(result: Set[Map[String, Any]]):
  Map[String, Any] = result.flatten.map(e => e._1 -> e._2).toMap

  def throwRequiredKeysNotFoundException(reqKeysNotFound: Set[String]): HandledErrorException = {
    new InternalServerErrorException(DATA_NOT_FOUND.statusCode, Option("required keys not found: " + reqKeysNotFound.mkString(", ")))
  }

  def buildUnexpectedResponse(r: Any): HandledErrorException = {
    r match {
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
