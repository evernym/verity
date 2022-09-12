package com.evernym.verity.cache

import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.cache.base.{Cache, CacheRequest, FetcherParam, GetCachedObjectParam, QueryResult, ReqParam, RespParam}
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.cache.fetchers.{AsyncCacheValueFetcher, CacheValueFetcher}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._


class CacheMaxSizeSpec
  extends ActorSpec
    with BasicSpec
    with Eventually {

  lazy val cache: Cache = buildCache()
  lazy val ecp = new ExecutionContextProvider(appConfig)
  implicit lazy val executionContext: ExecutionContext = ecp.futureExecutionContext

  "Cache" - {
    "when kept adding objects more than max size" - {
      "should respect the max size cap" in {
        (1 to 100).foreach { i =>
          val gcop = GetCachedObjectParam(ReqParam(GetMaxSizeCacheReq(i.toString, Right(i.toString)), required = true), mockFetcher)
          val fut = cache.getByParamAsync(gcop)
          val cqr = Await.result(fut, 2.second)
          cqr shouldBe QueryResult(Map(s"$i" -> s"$i"))
        }
        eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
          mockMaxSizeFetcher.maxSize shouldBe Option(20)
          cache.allCacheSize <= mockMaxSizeFetcher.maxSize.get shouldBe true
        }
      }
    }
  }

  lazy val mockFetcher: FetcherParam = FetcherParam(-1, "mock-cache")
  lazy val mockMaxSizeFetcher = new MockMaxSizeCacheFetcher(appConfig, executionContext)
  lazy val fetchers: Map[FetcherParam, AsyncCacheValueFetcher] = Map(mockFetcher -> mockMaxSizeFetcher)

  def buildCache(name: String = "MockCache", fetchers: Map[FetcherParam, CacheValueFetcher] = fetchers): Cache = {
    new Cache(name, fetchers, metricsWriter, executionContext)
  }

  override def executionContextProvider: ExecutionContextProvider = ecp
}

class MockMaxSizeCacheFetcher(val appConfig: AppConfig, ec: ExecutionContext)
  extends AsyncCacheValueFetcher {
  implicit val executionContext: ExecutionContext = ec
  lazy val fetcherParam: FetcherParam = FetcherParam(-1, "mock-cache")
  lazy val cacheConfigPath: Option[String] = None

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(300)
  override lazy val defaultMaxSize: Option[Int] = Option(20)

  override def toCacheRequests(rp: ReqParam): Set[CacheRequest] = {
    val gvp = rp.cmdAs[GetMaxSizeCacheReq]
    Set(CacheRequest(rp, gvp.id, gvp.id))
  }

  override def getByRequest(cr: CacheRequest): Future[Option[RespParam]] = {
    val getReq = cr.reqParam.cmdAs[GetMaxSizeCacheReq]
    val respFut = Future(getReq.resp)
    respFut.map {
      case Right(resp) => Option(RespParam(resp))
      case Left(d)     => throw buildUnexpectedResponse(d)
    }
  }

  /**
   * custom thread pool executor
   */
  override def futureExecutionContext: ExecutionContext = executionContext
}

/**
 *
 * @param id
 * @param resp resp will be sent back as the value, this is to be able to easily test different scenarios
 */
case class GetMaxSizeCacheReq(id: String, resp: Either[StatusDetail, AnyRef])