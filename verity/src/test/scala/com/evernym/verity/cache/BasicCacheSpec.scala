package com.evernym.verity.cache

import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.util2.ExecutionContextProvider
import com.evernym.verity.actor._
import com.evernym.verity.actor.cluster_singleton.{AddMapping, ForKeyValueMapper}
import com.evernym.verity.actor.testkit.{PersistentActorSpec, TestAppConfig}
import com.evernym.verity.cache.base.{Cache, CacheQueryResponse, FetcherParam, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.{AsyncCacheValueFetcher, CacheValueFetcher, KeyValueMapperFetcher}
import com.evernym.verity.testkit.{BasicAsyncSpec, CancelGloballyAfterFailure}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.Eventually

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration


class BasicCacheSpec
  extends PersistentActorSpec
    with BasicAsyncSpec
    with CancelGloballyAfterFailure
    with Eventually {

  override def beforeAll(): Unit = {
    val _ = platform.singletonParentProxy   //to make sure singleton proxy actor gets created before use
  }

  lazy val ecp = new ExecutionContextProvider(appConfig)
  override implicit lazy val executionContext: ExecutionContext = ecp.futureExecutionContext
  lazy val cache: Cache = buildCache()

  "Cache" - {
    "without any items cached" - {
      "should respond with size 0" in {
        cache.allCacheSize shouldBe 0
        cache.allCacheMissCount shouldBe 0
        cache.allCacheHitCount shouldBe 0
      }
    }

    "when asked for non existing key" - {
      "should get empty map" in {
        getFromCache(cache, "a", required = false).map { cqr =>
          cqr shouldBe CacheQueryResponse(Map.empty)
          cache.allCacheSize shouldBe 0
          cache.allCacheMissCount shouldBe 1
          cache.allCacheHitCount shouldBe 0
        }
      }
    }

    "when asked key value mapper object with key 'a'" - {
      "should get it from source and save it in cache" in {
        addKeyValueMapping(AddMapping("a", "apple"))

        getFromCache(cache, "a", required = true).map { cqr =>
          cqr shouldBe CacheQueryResponse(Map("a" -> "apple"))
          cache.allCacheSize shouldBe 1
          cache.allCacheMissCount shouldBe 2
          cache.allCacheHitCount shouldBe 0
        }
      }
    }

    "when asked key value mapper object with key 'a'" - {
      "should get it from cache" in {
        getFromCache(cache, "a", required = true).map { cqr =>
          cqr shouldBe CacheQueryResponse(Map("a" -> "apple"))
          cache.allCacheSize shouldBe 1
          cache.allCacheMissCount shouldBe 2
          cache.allCacheHitCount shouldBe 1
        }
      }
    }

    "when asked again for key value mapper object with key 'a' after waiting for expiration time" - {
      "should get it from source as the cache would have been expired" in {
        Thread.sleep(1500)
        getFromCache(cache, "a", required = true).map { cqr =>
          cqr shouldBe CacheQueryResponse(Map("a" -> "apple"))
          cache.allCacheSize shouldBe 1
          cache.allCacheMissCount shouldBe 3
          cache.allCacheHitCount shouldBe 1
        }
      }
    }

    "when asked for key value mapper object with key 'b'" - {
      "should respond with empty map" in {
        getFromCache(cache, "b", required = false).map { cqr =>
          cqr shouldBe CacheQueryResponse(Map.empty)
          cache.allCacheSize shouldBe 1
          cache.allCacheMissCount shouldBe 4
          cache.allCacheHitCount shouldBe 1
        }
      }
    }
  }

  "when asked for multiple values from cache" - {
    "should get it from from cache" in {
      addKeyValueMapping(AddMapping("b", "berry"))
      getFromCache(cache, Set(KeyDetail("a", required = true), KeyDetail("b", required = true))).map { cqr =>
        cqr shouldBe CacheQueryResponse(Map("a" -> "apple", "b" -> "berry"))
        cache.allCacheSize shouldBe 2
        cache.allCacheMissCount shouldBe 5
        cache.allCacheHitCount shouldBe 2
      }
    }
  }

  "when source is updated with new value" - {
    "should respond with updated value after expiry time" in {
      addKeyValueMapping(AddMapping("b", "blackberry"))
      Thread.sleep(1100)
      getFromCache(cache, Set(KeyDetail("a", required = true), KeyDetail("b", required = true))).map { cqr =>
        cqr shouldBe CacheQueryResponse(Map("a" -> "apple", "b" -> "blackberry"))
        cache.allCacheSize shouldBe 2
        cache.allCacheMissCount shouldBe 7
        cache.allCacheHitCount shouldBe 2
      }
    }
  }

  override def overrideConfig: Option[Config] = Option {
    ConfigFactory.parseString {
      """verity.cache.key-value-mapper {
         expiration-time-in-seconds = 1
         max-size = 10
      }""".stripMargin
    }
  }

  val keyValueMapperFetcher: FetcherParam = KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER
  val keyValueFetcher = new KeyValueMapperFetcher(system, appConfig, executionContext)
  val fetchers: Map[FetcherParam, AsyncCacheValueFetcher] = Map(
    keyValueMapperFetcher -> keyValueFetcher)

  def buildCache(name: String = "TestCache", fetchers: Map[FetcherParam, CacheValueFetcher] = fetchers): Cache = {
    new Cache(name, fetchers, metricsWriter, executionContext)
  }

  implicit val timeout: Timeout = Timeout(Duration.create(5, TimeUnit.SECONDS))


  def addKeyValueMapping(am: AddMapping): Unit = {
    val fut = platform.singletonParentProxy ? ForKeyValueMapper(am)
    val ma = Await.result(fut, Duration("5 sec")).asInstanceOf[MappingAdded]
    ma.key shouldBe am.key
    ma.value shouldBe am.value
  }

  def getFromCache(cache: Cache, key: String, required: Boolean): Future[CacheQueryResponse] = {
    getFromCache(cache, Set(KeyDetail(key, required)))
  }

  def getFromCache(cache: Cache, keyDetails: Set[KeyDetail]): Future[CacheQueryResponse] = {
    cache.getByParamAsync(GetCachedObjectParam(keyDetails, keyValueMapperFetcher))
  }

  override def executionContextProvider: ExecutionContextProvider = ecp
}
