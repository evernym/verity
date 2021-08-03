package com.evernym.verity.cache

import com.evernym.verity.actor.testkit.{ActorSpec, TestAppConfig}
import com.evernym.verity.cache.base.{Cache, FetcherParam, GetCachedObjectParam, KeyDetail, KeyMapping}
import com.evernym.verity.util2.Status.StatusDetail
import com.evernym.verity.cache.fetchers.{AsyncCacheValueFetcher, CacheValueFetcher}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.testkit.BasicAsyncSpec
import com.evernym.verity.util2.ExecutionContextProvider
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.{ExecutionContext, Future}

class CacheMaxWeightSpec
  extends ActorSpec
    with BasicAsyncSpec
    with Eventually{

  lazy val cache: Cache = buildCache()

  "Cache" - {
    "when asked for object 1" - {
      "should respond with appropriate value" in {
        val value = Array.range(0, 270).map(_.toByte)
        val gcop = GetCachedObjectParam(KeyDetail(GetMaxWeightCacheReq("1", Right(value)), required = true), mockFetcher)
        cache.getByParamAsync(gcop).map { _ =>
          cache.allKeys shouldBe Set("1")
          cache.allCacheHitCount shouldBe 0
          cache.allCacheMissCount shouldBe 1
        }
      }
    }

    "when asked for object 2" - {
      "should respond with appropriate value" in {
        val value = Array.range(0, 270).map(_.toByte)
        val gcop = GetCachedObjectParam(KeyDetail(GetMaxWeightCacheReq("2", Right(value)), required = true), mockFetcher)
        cache.getByParamAsync(gcop).map { _ =>
          cache.allKeys shouldBe Set("1", "2")
          cache.allCacheHitCount shouldBe 0
          cache.allCacheMissCount shouldBe 2
        }
      }
    }

    "when asked for object 3" - {
      "should respond with appropriate value" in {
        val value = Array.range(0, 270).map(_.toByte)
        val gcop = GetCachedObjectParam(KeyDetail(GetMaxWeightCacheReq("3", Right(value)), required = true), mockFetcher)
        cache.getByParamAsync(gcop).map { _ =>
          cache.allKeys shouldBe Set("1", "2", "3")
          cache.allCacheHitCount shouldBe 0
          cache.allCacheMissCount shouldBe 3
        }
      }
    }

    "when asked for a larger object" - {
      "should evict previous cached items to accommodate new item" in {
        //1MB is the total weight for the cache, so anything which makes
        // total cache size to cross ~1MB should result in eviction
        val largerObject = Array.range(0, 270).map(_.toByte)
        val gcop = GetCachedObjectParam(KeyDetail(GetMaxWeightCacheReq("larger", Right(largerObject)), required = true), mockFetcher)
        cache.getByParamAsync(gcop).map { _ =>
          eventually(timeout(Span(10, Seconds)), interval(Span(300, Millis))) {
            cache.allKeys shouldBe Set("2", "3", "larger")
            cache.allCacheHitCount shouldBe 0
            cache.allCacheMissCount shouldBe 4
          }
        }
      }
    }

    "when asked for a larger object again" - {
      "should be responded from the cache itself" in {
        val gcop = GetCachedObjectParam(KeyDetail(GetMaxWeightCacheReq("larger", Right("test")), required = true), mockFetcher)
        cache.getByParamAsync(gcop).map { _ =>
          cache.allKeys shouldBe Set("2", "3", "larger")
          cache.allCacheHitCount shouldBe 1
          cache.allCacheMissCount shouldBe 4
        }
      }
    }
  }

  lazy val mockFetcher: FetcherParam = FetcherParam(-1, "mock-cache")
  lazy val ecp = new ExecutionContextProvider(appConfig)
  override lazy implicit val executionContext: ExecutionContext = ecp.futureExecutionContext
  lazy val mockMaxWeightObjectFetcher = new MockMaxWeightCacheFetcher(executionContext, appConfig)
  lazy val fetchers: Map[FetcherParam, AsyncCacheValueFetcher] = Map(mockFetcher -> mockMaxWeightObjectFetcher)

  override def executionContextProvider: ExecutionContextProvider = ecp

  def buildCache(name: String = "MockCache", fetchers: Map[FetcherParam, CacheValueFetcher] = fetchers): Cache = {
    new Cache(name, fetchers, metricsWriter, executionContext)
  }
}

class MockMaxWeightCacheFetcher(ec: ExecutionContext, val _appConfig: AppConfig)
  extends AsyncCacheValueFetcher {
  implicit val executionContext: ExecutionContext = ec

  lazy val fetcherParam: FetcherParam = FetcherParam(-1, "mock-cache")
  lazy val cacheConfigPath: Option[String] = None

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(300)
  override lazy val defaultMaxWeightInBytes: Option[Long] = Option(1000)
  override lazy val defaultMaxSize: Option[Int] = Option(100)   //this won't matter as the max weight will take precedence

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gvp = kd.keyAs[GetMaxWeightCacheReq]
      KeyMapping(kd, gvp.id, gvp.id)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val getReq = kd.keyAs[GetMaxWeightCacheReq]
    val respFut = Future(getReq.resp)
    respFut.map {
      case Right(resp) => Map(getReq.id -> resp)
      case Left(d)     => throw buildUnexpectedResponse(d)
    }
  }

  override def appConfig: AppConfig = _appConfig

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
case class GetMaxWeightCacheReq(id: String, resp: Either[StatusDetail, AnyRef])