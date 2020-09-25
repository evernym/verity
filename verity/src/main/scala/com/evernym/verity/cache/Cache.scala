package com.evernym.verity.cache

import java.time.ZonedDateTime
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

import scala.concurrent.Future


case class KeyDetail(key: Any, required: Boolean)

case class KeyMapping(kd: KeyDetail, cacheKey: Any, outputKey: Any)


trait CacheValueFetcher {

  val logger: Logger = getLoggerByName("CacheValueFetcher")

  def id: Int

  def ttls: Option[Int]

  //NOTE: this provides mapping between key detail, key to be used in cache and key to be used for display purposes
  def getKeyDetailMapping(kws: Set[KeyDetail]): Set[KeyMapping]

  def composeMultiKeyDetailResult(result: Set[Map[Any, Any]]):
    Map[Any, Any] = result.flatten.map(e => e._1 -> e._2).toMap

  def throwRequiredKeysNotFoundException(reqKeysNotFound: Set[Any]): HandledErrorException = {
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
  def getByKeyDetail(kd: KeyDetail): Map[Any, Any]

  def getByKeyDetails(kds: Set[KeyDetail]): Map[Any, Any] = {
    val result = kds.map { kd =>
      getByKeyDetail(kd)
    }
    composeMultiKeyDetailResult(result)
  }
}


trait AsyncCacheValueFetcher extends CacheValueFetcher {

  def getByKeyDetail(kd: KeyDetail): Future[Map[Any, Any]]

  def getByKeyDetails(kds: Set[KeyDetail]): Future[Map[Any, Any]] = {
    Future.traverse(kds) { kd =>
      getByKeyDetail(kd)
    } flatMap { result =>
      Future {
        composeMultiKeyDetailResult(result)
      }
    }
  }
}

case class CachedObject(fetcherId: Int, key: Any, value: Option[Any],
                        createdDateTime: ZonedDateTime, ttls: Option[Int] = None) {

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
    s"fetcherId: $fetcherId, key: $key, createdDateTime: $createdDateTime, ttls: $ttls"
  }
}

case class GetCachedObjectParam(kds: Set[KeyDetail], fetcherId: Int,
                                ttls: Option[Int] = None, cacheOnlyIfValueFound: Boolean = true)


case class CachePreFetchResult(
                                              gcop: GetCachedObjectParam,
                                              reqId: String,
                                              keyMappings: Set[KeyMapping],
                                              fetcherTtls: Option[Int],
                                              requestedCacheKeys: Set[Any],
                                              cachedObjectsFound: Set[CachedObject],
                                              keysFoundInCache: Set[Any],
                                              keysNotFoundInCache: Set[Any],
                                              originalKeysNotFound: Set[KeyDetail]
                                            )

trait CacheBase {
  //TODO: as of now NOT using any distributed cache
  // so in case of multi node cluster,
  // there will be edge cases wherein same purpose cache exists on different nodes
  // and they may give different values for same key for certain time (based on cache expiration time you configured)

  val logger: Logger = getLoggerByClass(classOf[CacheBase])

  def name: String

  def fetchers: Map[Int, CacheValueFetcher]

  private var cachedObjects: List[CachedObject] = List.empty

  private var hit: Int = 0

  private var miss: Int = 0

  def size: Int = cachedObjects.size

  def getUnexpiredCachedObjectsByFetcherId(fetcherId: Int): List[CachedObject] = {
    cachedObjects.filter(co => co.fetcherId == fetcherId && co.value.isDefined && ! co.isExpired)
  }

  def addToCachedObject(co: CachedObject): Unit = {
    logger.debug("cached object added: " + co)
    cachedObjects = cachedObjects.filterNot(_.key == co.key)
    cachedObjects = cachedObjects :+ co
  }

  def deleteFromCache(keys: Set[Any]): Unit = {
    cachedObjects = cachedObjects.filterNot(k => keys.contains(k.key))
  }

  def getCacheHitRatio: String = {
    val total = hit + miss
    if (total > 0) {
      val per = hit*100.0f/total
      f"$per%1.2f" + "%"
    } else "n/a"
  }

  def logMsg(fetcherId: Int, reqId: String, msg: String): Unit = {
    logger.debug(s"[CACHE ($name:$fetcherId)] crid: [$reqId] " +
      s"(tid: [${Thread.currentThread().getId}]) => msg = $msg")
  }

  def logHitRatio(fetcherId: Int, reqId: String): Unit = {
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

  private def processFetchedData(fetchResult: Map[Any, Any])
                        (implicit ipr: CachePreFetchResult): Map[Any, Any] = {
    logMsg(ipr.gcop.fetcherId, ipr.reqId, "fetched data from source: " + fetchResult)
    fetchResult.foreach { case (k, v) =>
      val ettls = (ipr.gcop.ttls ++ ipr.fetcherTtls).headOption
      if (ettls.forall(_ > 0))
        addToCachedObject(CachedObject(ipr.gcop.fetcherId,
          k.toString, Option(v), ZonedDateTime.now(), ettls))
    }
    val found = ipr.cachedObjectsFound.map(fc => fc.key -> fc.value.orNull).toMap
    found ++ fetchResult
  }

  private def prepareFinalResponse(finalResult: Map[Any, Any])
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

  private def getPreFetchResult(fetcher: CacheValueFetcher)(implicit gcop: GetCachedObjectParam):
  CachePreFetchResult = {
    val id = UUID.randomUUID.toString
    logMsg(gcop.fetcherId, id, "input param: " + gcop)
    val fetcher = getFetcherById(gcop.fetcherId)
    val keyMappings = fetcher.getKeyDetailMapping(gcop.kds)
    val requestedCacheKeys = keyMappings.map(_.cacheKey)

    val cachedObjectsFound = cachedObjects.filter(c => requestedCacheKeys.contains(c.key) &&
      c.fetcherId == gcop.fetcherId && ! c.isExpired).toSet
    val keysFoundInCache = cachedObjectsFound.map(_.key)
    val keysNotFoundInCache = requestedCacheKeys.diff(keysFoundInCache)
    val originalKeysNotFound = keyMappings.filter(km => keysNotFoundInCache.contains(km.cacheKey)).map(_.kd)
    logMsg(gcop.fetcherId, id, "keys found in cache : " + keyMappings.filter(km =>
      keysFoundInCache.contains(km.cacheKey)).map { k =>
        k.outputKey + s"${cachedObjectsFound.find(_.key == k.cacheKey).map(k =>
          s" (${k.getExpirationDetail})").getOrElse("")}"
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

    implicit val cpfr: CachePreFetchResult = getPreFetchResult(fetcher)

    val finalResult = if (cpfr.keysNotFoundInCache.nonEmpty) {
      processFetchedData(fetcher.getByKeyDetails(cpfr.originalKeysNotFound))
    } else {
      cpfr.cachedObjectsFound.map(fc => fc.key -> fc.value.orNull).toMap
    }

    prepareFinalResponse(finalResult)
  }

  def getByParamAsync(implicit gcop: GetCachedObjectParam): Future[CacheQueryResponse] = {

    val fetcher = getAsyncFetcherById(gcop.fetcherId)

    implicit val cpfr: CachePreFetchResult = getPreFetchResult(fetcher)

    val finalResult = if (cpfr.keysNotFoundInCache.nonEmpty) {
      fetcher.getByKeyDetails(cpfr.originalKeysNotFound).map { r =>
        processFetchedData(r)
      }.recover {
        case e: Exception => throw e
      }
    } else {
      Future(cpfr.cachedObjectsFound.map(fc => fc.key -> fc.value.orNull).toMap)
    }

    finalResult map { fr =>
      prepareFinalResponse(fr)
    }
  }


}

trait CacheResponseUtil {

  def data: Map[Any, Any]

  def get(key: Any): Option[Any] = data.get(key)

  def getStringOpt(key: Any): Option[String] = get(key).map(_.toString)

  def getStringReq(key: Any): String = getStringOpt(key).getOrElse (
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("value not found for given key" + key))
  )

  def getConfigs: AgentConfigs = AgentConfigs(data.map(e => ConfigDetail(e._1.toString, e._2.toString)).toSet)

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

case class CacheQueryResponse(data: Map[Any, Any]) extends CacheResponseUtil

class Cache(override val name: String, override val fetchers: Map[Int, CacheValueFetcher]) extends CacheBase