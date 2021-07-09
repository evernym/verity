package com.evernym.verity.cache

import com.evernym.verity.actor.testkit.ActorSpec
import com.evernym.verity.cache.base.{Cache, CacheQueryResponse, FetcherParam, GetCachedObjectParam, KeyDetail, KeyMapping}
import com.evernym.verity.Status.StatusDetail
import com.evernym.verity.cache.fetchers.{AsyncCacheValueFetcher, CacheValueFetcher}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.metrics.{MetricsWriterExtension, MetricsWriterExtensionImpl}
import com.evernym.verity.testkit.BasicSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


class CacheMaxSizeSpec
  extends ActorSpec
    with BasicSpec
    with Eventually {

  lazy val cache: Cache = buildCache()

  "Cache" - {
    "when kept adding objects more than max size" - {
      "should respect the max size cap" in {
        (1 to 100).foreach { i =>
          val gcop = GetCachedObjectParam(KeyDetail(GetMaxSizeCacheReq(i.toString, Right(i.toString)), required = true), mockFetcher)
          val fut = cache.getByParamAsync(gcop)
          val cqr = Await.result(fut, 2.second)
          cqr shouldBe CacheQueryResponse(Map(s"$i" -> s"$i"))
        }
        eventually(timeout(Span(5, Seconds)), interval(Span(100, Millis))) {
          mockMaxSizeFetcher.maxSize shouldBe Option(20)
          cache.allCacheSize <= mockMaxSizeFetcher.maxSize.get shouldBe true
        }
      }
    }
  }

  lazy val mockFetcher: FetcherParam = FetcherParam(-1, "mock-cache")
  lazy val mockMaxSizeFetcher = new MockMaxSizeCacheFetcher(appConfig)
  lazy val fetchers: Map[FetcherParam, AsyncCacheValueFetcher] = Map(mockFetcher -> mockMaxSizeFetcher)

  def buildCache(name: String = "MockCache", fetchers: Map[FetcherParam, CacheValueFetcher] = fetchers): Cache = {
    new Cache(name, fetchers, MetricsWriterExtension(platform.actorSystem))
  }
}

class MockMaxSizeCacheFetcher(val appConfig: AppConfig)
  extends AsyncCacheValueFetcher {

  import com.evernym.verity.ExecutionContextProvider.futureExecutionContext

  lazy val fetcherParam: FetcherParam = FetcherParam(-1, "mock-cache")
  lazy val cacheConfigPath: Option[String] = None

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(300)
  override lazy val defaultMaxSize: Option[Int] = Option(20)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gvp = kd.keyAs[GetMaxSizeCacheReq]
      KeyMapping(kd, gvp.id, gvp.id)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val getReq = kd.keyAs[GetMaxSizeCacheReq]
    val respFut = Future(getReq.resp)
    respFut.map {
      case Right(resp) => Map(getReq.id -> resp)
      case Left(d)     => throw buildUnexpectedResponse(d)
    }
  }
}

/**
 *
 * @param id
 * @param resp resp will be sent back as the value, this is to be able to easily test different scenarios
 */
case class GetMaxSizeCacheReq(id: String, resp: Either[StatusDetail, AnyRef])