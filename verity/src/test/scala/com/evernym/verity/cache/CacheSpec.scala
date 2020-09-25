package com.evernym.verity.cache

import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor._
import com.evernym.verity.actor.cluster_singleton.{AddMapping, ForKeyValueMapper}
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.constants.Constants._
import com.evernym.verity.testkit.{BasicAsyncSpec, CancelGloballyAfterFailure}

import scala.concurrent.Future
import scala.concurrent.duration.Duration


class CacheSpec extends PersistentActorSpec with BasicAsyncSpec with CancelGloballyAfterFailure {
  implicit val timeout: Timeout = Timeout(Duration.create(5, TimeUnit.SECONDS))

  var cache: Cache = _
  val keyValueMapperFetcherId: Int = KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID
  val fetchers: Map[Int, AsyncCacheValueFetcher] = Map(
    keyValueMapperFetcherId -> new KeyValueMapperFetcher(system, appConfig))

  def addKeyValueMapping(am: AddMapping): Future[Any] = {
    platform.singletonParentProxy ? ForKeyValueMapper(am)
  }

  "Cache" - {
    "when initialized" - {
      "should have no cached config" in {
        platform.singletonParentProxy   //to make sure singleton proxy actor gets created before use

        cache = new Cache("TestCache", fetchers)
        cache.size shouldBe 0
      }
    }
    "when asked key value mapper object" - {
      "should get empty map as there is no such value stored" in {
        val fut = cache.getByParamAsync(GetCachedObjectParam(Set(
          KeyDetail("1", required = false)), keyValueMapperFetcherId, Option(0)))
        fut map { v =>
          v shouldBe CacheQueryResponse(Map.empty)
        }
      }
    }
  }

  "KeyValue Mapper actor" - {
    "when sent AddMapping" - {
      "should be able to add the mapping" in {
        addKeyValueMapping(AddMapping("1", "one")) map {
          case ma: MappingAdded =>
            ma.key shouldBe "1"
            ma.value shouldBe "one"
        }
      }
    }
  }

  "Cache" - {
    "when asked key value mapper object with key '1'" - {
      "should get it from source and save it in cache" in {
        val fut = cache.getByParamAsync(GetCachedObjectParam(Set(
          KeyDetail("1", required = false)), keyValueMapperFetcherId, Option(1)))
        fut map { v =>
          v shouldBe CacheQueryResponse(Map("1" -> "one"))
          cache.size shouldBe 1
        }
      }
    }

    "when asked again for key value mapper object with key '1'" - {
      "should get it from cache only" in {
        val fut = cache.getByParamAsync(GetCachedObjectParam(Set(
          KeyDetail("1", required = false)), keyValueMapperFetcherId))
        fut map { v =>
          v shouldBe CacheQueryResponse(Map("1" -> "one"))
        }
      }
    }

    "when asked again for key value mapper object with key '1' after waiting for around a second" - {
      "should get it from source as the cache would have been expired" in {
        Thread.sleep(1100)
        val fut = cache.getByParamAsync(GetCachedObjectParam(Set(
          KeyDetail("1", required = false)), keyValueMapperFetcherId))
        fut map { v =>
          v shouldBe CacheQueryResponse(Map("1" -> "one"))
          cache.size shouldBe 1
        }
      }
    }

    "when asked for key value mapper object with key '2'" - {
      "should get it from source" in {
        val fut = cache.getByParamAsync(GetCachedObjectParam(Set(
          KeyDetail("2", required = false)), keyValueMapperFetcherId))
        fut map { v =>
          v shouldBe CacheQueryResponse(Map.empty)
        }
      }
    }
  }

  "KeyValue Mapper actor" - {
    "when sent new add mappings" - {
      "should be able to add it successfully" in {
        addKeyValueMapping(AddMapping("2", "two")) map {
          case ma: MappingAdded =>
            ma.key shouldBe "2"
            ma.value shouldBe "two"
        }
      }
    }
  }

  "when asked for multiple values from cache" - {
    "should get it from from cache" in {
      val fut = cache.getByParamAsync(GetCachedObjectParam(Set(
        KeyDetail("1", required = false), KeyDetail("2", required = false)), keyValueMapperFetcherId, Option(3)))
      fut map { v =>
        v shouldBe CacheQueryResponse(Map("1" -> "one", "2" -> "two"))
      }
      addKeyValueMapping(AddMapping("2", "updated")) map {
        case ma: MappingAdded =>
          ma.key shouldBe "2"
          ma.value shouldBe "updated"
      }
      val fut1 = cache.getByParamAsync(GetCachedObjectParam(Set(
        KeyDetail("1", required = false), KeyDetail("2", required = false)), keyValueMapperFetcherId))
      fut1 map { v =>
        v shouldBe CacheQueryResponse(Map("1" -> "one", "2" -> "two"))
      }
      Thread.sleep(3000)
      val fut2 = cache.getByParamAsync(GetCachedObjectParam(Set(
        KeyDetail("1", required = false), KeyDetail("2", required = false)), keyValueMapperFetcherId))
      fut2 map { v =>
        v shouldBe CacheQueryResponse(Map("1" -> "one", "2" -> "updated"))
      }
    }
  }

}
