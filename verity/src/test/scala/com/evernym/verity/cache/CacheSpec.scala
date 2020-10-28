package com.evernym.verity.cache

import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.actor._
import com.evernym.verity.actor.cluster_singleton.{AddMapping, ForKeyValueMapper}
import com.evernym.verity.actor.testkit.PersistentActorSpec
import com.evernym.verity.constants.Constants._
import com.evernym.verity.testkit.{BasicAsyncSpec, CancelGloballyAfterFailure}

import scala.concurrent.Await
import scala.concurrent.duration.Duration


class CacheSpec extends PersistentActorSpec with BasicAsyncSpec with CancelGloballyAfterFailure {

  "Cache" - {
    "when initialized" - {
      "should have no cached config" in {
        val _ = platform.singletonParentProxy   //to make sure singleton proxy actor gets created before use

        cache = new Cache("TestCache", fetchers)
        cache.size shouldBe 0
      }
    }
    "when asked key value mapper object" - {
      "should get empty map as there is no such value stored" in {
        val cqr = getSingleFromCache("1", required=false)
        cqr shouldBe CacheQueryResponse(Map.empty)
      }
    }
  }

  "KeyValue Mapper actor" - {
    "when sent AddMapping" - {
      "should be able to add the mapping" in {
        val ma = addKeyValueMapping(AddMapping("1", "one"))
        ma.key shouldBe "1"
        ma.value shouldBe "one"
      }
    }
  }

  "Cache" - {
    "when asked key value mapper object with key '1'" - {
      "should get it from source and save it in cache" in {
        val cqr = getSingleFromCache("1", required=false, ttls = 1)
        cqr shouldBe CacheQueryResponse(Map("1" -> "one"))
        cache.size shouldBe 1
      }
    }

    "when asked again for key value mapper object with key '1'" - {
      "should get it from cache only" in {
        val cqr = getSingleFromCache("1", required=false)
        cqr shouldBe CacheQueryResponse(Map("1" -> "one"))
      }
    }

    "when asked again for key value mapper object with key '1' after waiting for around a second" - {
      "should get it from source as the cache would have been expired" in {
        Thread.sleep(1100)
        val cqr = getSingleFromCache("1", required=false)
        cqr shouldBe CacheQueryResponse(Map("1" -> "one"))
        cache.size shouldBe 1
      }
    }

    "when asked for key value mapper object with key '2'" - {
      "should get it from source" in {
        val cqr = getSingleFromCache("2", required=false)
        cqr shouldBe CacheQueryResponse(Map.empty)
      }
    }
  }

  "KeyValue Mapper actor" - {
    "when sent new add mappings" - {
      "should be able to add it successfully" in {
        val ma = addKeyValueMapping(AddMapping("2", "two"))
        ma.key shouldBe "2"
        ma.value shouldBe "two"
      }
    }
  }

  "when asked for multiple values from cache" - {
    "should get it from from cache" in {
      val cqr = getFromCache(Set(KeyDetail("1", required=false), KeyDetail("2", required = false)), ttls = 3)
      cqr shouldBe CacheQueryResponse(Map("1" -> "one", "2" -> "two"))

      val ma = addKeyValueMapping(AddMapping("2", "updated"))
      ma.key shouldBe "2"
      ma.value shouldBe "updated"

      Thread.sleep(4000)

      val newCqr = getFromCache(Set(KeyDetail("1", required=false), KeyDetail("2", required = false)), ttls = 3)
      newCqr shouldBe CacheQueryResponse(Map("1" -> "one", "2" -> "updated"))
    }
  }

  implicit val timeout: Timeout = Timeout(Duration.create(5, TimeUnit.SECONDS))

  var cache: Cache = _
  val keyValueMapperFetcherId: Int = KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID
  val fetchers: Map[Int, AsyncCacheValueFetcher] = Map(
    keyValueMapperFetcherId -> new KeyValueMapperFetcher(system, appConfig))

  def addKeyValueMapping(am: AddMapping): MappingAdded = {
    val fut = platform.singletonParentProxy ? ForKeyValueMapper(am)
    Await.result(fut, Duration("5 sec")).asInstanceOf[MappingAdded]
  }

  def getSingleFromCache(key: String, required: Boolean, ttls: Int = 0): CacheQueryResponse = {
    getFromCache(Set(KeyDetail(key, required)), ttls)
  }

  def getFromCache(keyDetails: Set[KeyDetail], ttls: Int = 0): CacheQueryResponse = {
    val fut = cache.getByParamAsync(GetCachedObjectParam(keyDetails, keyValueMapperFetcherId, Option(ttls)))
    Await.result(fut, Duration("5 sec"))
  }
}
