package com.evernym.verity.cache

import akka.util.Timeout
import com.evernym.verity.actor.agent.agency.{AgencyInfo, GetAgencyIdentity}
import com.evernym.verity.actor.testkit.{CommonSpecUtil, PersistentActorSpec}
import com.evernym.verity.actor.testkit.actor.MockLegacyLedgerSvc
import com.evernym.verity.cache.base.{Cache, CacheRequest, CacheKey, FetcherParam, GetCachedObjectParam, QueryResult, ReqParam, RespParam}
import com.evernym.verity.cache.fetchers.{AsyncCacheValueFetcher, CacheValueFetcher, GetAgencyIdentityCacheParam}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.AGENCY_DETAIL_CACHE
import com.evernym.verity.did.{DidStr, VerKeyStr}
import com.evernym.verity.testkit.BasicAsyncSpec
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.util2.{ExecutionContextProvider, Status}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class AgencyIdentityCacheSpec
  extends PersistentActorSpec
  with BasicAsyncSpec
  with Eventually {

  val agencyInfoProvider = new AgencyInfoProvider()

  override def beforeAll(): Unit = {
    val _ = platform.singletonParentProxy //to make sure singleton proxy actor gets created before use
    updateAgencyInfoProvider(agencyDid1, Option("verkey"), Option("http://test.endpoint.com"))
  }

  lazy val ecp = new ExecutionContextProvider(appConfig)
  override implicit lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  lazy val cache: Cache = buildCache()

  val localAgencyDid: DidStr = CommonSpecUtil.generateNewDid().did
  val agencyDid1: DidStr = CommonSpecUtil.generateNewDid().did

  "Cache" - {
    "without any items cached" - {
      "should respond with size 0" in {
        cache.allCacheSize shouldBe 0
        cache.allCacheMissCount shouldBe 0
        cache.allCacheHitCount shouldBe 0
      }
    }

    "when asked for agency identity for agencyDid1 without endpoint" - {
      "should get it from source and save it in cache" in {
        getFromCache(cache, Set(buildReqParam(agencyDid1, getEndpoint = false, required = true))).map { cqr =>
          cqr shouldBe QueryResult(Map(agencyDid1 -> AgencyInfo(Option(Right("verkey")), None)))
          cache.allCacheSize shouldBe 1
          cache.allCacheMissCount shouldBe 1
          cache.allCacheHitCount shouldBe 0
        }
      }
    }

    "when asked for agency identity again for agencyDid1 without endpoint" - {
      "should get it from cache" in {
        getFromCache(cache, Set(buildReqParam(agencyDid1, getEndpoint = false, required = true))).map { cqr =>
          cqr shouldBe QueryResult(Map(agencyDid1 -> AgencyInfo(Option(Right("verkey")), None)))
          cache.allCacheSize shouldBe 1
          cache.allCacheMissCount shouldBe 1
          cache.allCacheHitCount shouldBe 1
        }
      }
    }

    "when asked for agency identity for agencyDid1 without verkey" - {
      "should get it from source and save it in cache" in {

        getFromCache(cache, Set(buildReqParam(agencyDid1, getVerKey = false, required = true))).map { cqr =>
          cqr shouldBe QueryResult(Map(agencyDid1 -> AgencyInfo(None, Option(Right("http://test.endpoint.com")))))
          cache.allCacheSize shouldBe 2
          cache.allCacheMissCount shouldBe 2
          cache.allCacheHitCount shouldBe 1
        }
      }
    }

    "when asked for agency identity again for agencyDid1 without verkey" - {
      "should get it from cache" in {

        getFromCache(cache, Set(buildReqParam(agencyDid1, getVerKey = false, required = true))).map { cqr =>
          cqr shouldBe QueryResult(Map(agencyDid1 -> AgencyInfo(None, Option(Right("http://test.endpoint.com")))))
          cache.allCacheSize shouldBe 2
          cache.allCacheMissCount shouldBe 2
          cache.allCacheHitCount shouldBe 2
        }
      }
    }

    "when asked for agency identity for agencyDid1" - {
      "should get it from source and save it in cache" in {
        getFromCache(cache, Set(buildReqParam(agencyDid1, required = true))).map { cqr =>
          cqr shouldBe QueryResult(Map(agencyDid1 -> AgencyInfo(Option(Right("verkey")), Option(Right("http://test.endpoint.com")))))
          cache.allCacheSize shouldBe 3
          cache.allCacheMissCount shouldBe 3
          cache.allCacheHitCount shouldBe 2
        }
      }
    }

    "when asked for agency identity again for agencyDid1" - {
      "should get it from cache" in {
        getFromCache(cache, Set(buildReqParam(agencyDid1, required = true))).map { cqr =>
          cqr shouldBe QueryResult(Map(agencyDid1 -> AgencyInfo(Option(Right("verkey")), Option(Right("http://test.endpoint.com")))))
          cache.allCacheSize shouldBe 3
          cache.allCacheMissCount shouldBe 3
          cache.allCacheHitCount shouldBe 3
        }
      }
    }
  }

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory.parseString {
      """verity.cache.agency-detail {
       expiration-time-in-seconds = 1
       max-size = 10
    }""".stripMargin
    }
  }

  private def buildReqParam(did: DidStr,
                            getVerKey: Boolean=true,
                            getEndpoint: Boolean=true,
                            required: Boolean=false): ReqParam = {
    ReqParam(GetAgencyIdentityCacheParam(localAgencyDid, GetAgencyIdentity(did, getVerKey, getEndpoint)), required)
  }

  val agencyIdentityCacheFetcherParam: FetcherParam = AGENCY_IDENTITY_CACHE_FETCHER
  val agencyIdentityCacheFetcher = new MockAgencyIdentityCacheFetcher(agencyInfoProvider, appConfig, executionContext)
  val fetchers: Map[FetcherParam, AsyncCacheValueFetcher] = Map(
    agencyIdentityCacheFetcherParam -> agencyIdentityCacheFetcher)

  def buildCache(name: String = "TestCache", fetchers: Map[FetcherParam, CacheValueFetcher] = fetchers): Cache = {
    new Cache(name, fetchers, metricsWriter, executionContext)
  }

  implicit val timeout: Timeout = Timeout(Duration.create(5, TimeUnit.SECONDS))

  lazy val mockLegacyLedger: MockLegacyLedgerSvc = platform.agentActorContext.legacyLedgerSvc.asInstanceOf[MockLegacyLedgerSvc]

  def updateAgencyInfoProvider(agencyDid: DidStr,
                               verKey: Option[VerKeyStr],
                               endpoint: Option[String]): Unit = {
    agencyInfoProvider.updateAgencyInfo(
      agencyDid,
      AgencyInfo(
        verKey.map(vk => Right(vk)),
        endpoint.map(ep => Right(ep))
      )
    )
  }

  def getFromCache(cache: Cache, rps: Set[ReqParam]): Future[QueryResult] = {
    cache.getByParamAsync(GetCachedObjectParam(rps, agencyIdentityCacheFetcherParam))
  }

  override def executionContextProvider: ExecutionContextProvider = ecp
}

class MockAgencyIdentityCacheFetcher(val agencyInfoProvider: AgencyInfoProvider,
                                     val appConfig: AppConfig,
                                     executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher{

  override def futureExecutionContext: ExecutionContext = executionContext
  private implicit val executionContextImpl: ExecutionContext = executionContext

  lazy val fetcherParam: FetcherParam = AGENCY_IDENTITY_CACHE_FETCHER

  lazy val cacheConfigPath: Option[String] = Option(AGENCY_DETAIL_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(1800)

  override def toCacheRequests(rp: ReqParam): Set[CacheRequest] = {
    val gadcp = rp.cmdAs[GetAgencyIdentityCacheParam]
    Set(CacheRequest(rp, gadcp.gad.did, buildLookupKey(gadcp.gad)))
  }

  override def getByRequest(cr: CacheRequest): Future[Option[RespParam]] = {
    val gadcp = cr.reqParam.cmdAs[GetAgencyIdentityCacheParam]
    val verKeyResult = if (gadcp.gad.getVerKey) agencyInfoProvider.getVerKey(gadcp.gad.did) else None
    val endpointResult = if (gadcp.gad.getEndpoint) agencyInfoProvider.getEndpoint(gadcp.gad.did) else None
    val gadFutResp = Future.successful(AgencyInfo(verKeyResult, endpointResult))
    gadFutResp.map {
      case ai: AgencyInfo if ! ai.isErrorFetchingAnyData =>
        logger.info(s"agency info received from source for '${gadcp.gad.did}': " + ai)
        Option(RespParam(ai))
      case ai: AgencyInfo if ai.verKeyErrorOpt.isDefined =>
        throw buildUnexpectedResponse(ai.verKeyErrorOpt.get)
      case ai: AgencyInfo if ai.endpointErrorOpt.isDefined =>
        throw buildUnexpectedResponse(ai.endpointErrorOpt.get)
      case x => throw buildUnexpectedResponse(x)
    }
  }

  private def buildLookupKey(gad: GetAgencyIdentity): CacheKey = s"${gad.did}:${gad.getVerKey}:${gad.getEndpoint}"
}

class AgencyInfoProvider {
  private var agencyAgents: Map[DidStr, AgencyInfo] = Map.empty

  def updateAgencyInfo(did: DidStr, agencyInfo: AgencyInfo): Unit = {
    agencyAgents = agencyAgents ++ Map(did -> agencyInfo)
  }

  def getVerKey(did: DidStr): Option[Either[StatusDetail, VerKeyStr]] = {
    agencyAgents
      .get(did)
      .map(_.verKey)
      .getOrElse(Option(Left(Status.DATA_NOT_FOUND)))
  }

  def getEndpoint(did: DidStr): Option[Either[StatusDetail, String]] = {
    agencyAgents
      .get(did)
      .map(_.endpoint)
      .getOrElse(Option(Left(Status.DATA_NOT_FOUND)))
  }
}