package com.evernym.verity.cache.providers

import com.github.blemale.scaffeine.{Scaffeine, Cache => ScaffeineCache}
import scala.concurrent.duration._

class CaffeineCacheProvider(cacheParam: CaffeineCacheParam)
  extends CacheProvider {

  var cache: ScaffeineCache[String, AnyRef] = {
    val builder = Scaffeine().recordStats()

    val builderWithInitialCapacity = cacheParam.initialCapacity match {
      case Some(ic) => builder.initialCapacity(ic)
      case _        => builder
    }

    val builderWithExpiryTime = cacheParam.expiryTimeInSeconds match {
      case Some(time)  => builderWithInitialCapacity.expireAfterWrite(time.second)
      case None        => builderWithInitialCapacity
    }

    val builderWithMaxCapacity =
      (cacheParam.maxSize, cacheParam.maxWeightParam) match {
        case (Some(ms), None)   => builderWithExpiryTime.maximumSize(ms)
        case (None, Some(mwp))  => builderWithExpiryTime.maximumWeight(mwp.maxWeight).weigher(mwp.weigher)
        case _                  => builderWithExpiryTime
    }

    builderWithMaxCapacity.build[String, AnyRef]()
  }

  override def cachedObjects: Map[String, AnyRef] = cache.asMap().toMap
  override def put(key: String, value: AnyRef): Unit = cache.put(key, value)
  override def get(key: String): Option[AnyRef] = cache.getIfPresent(key)

  override def size: Int = cachedObjects.size
  override def hitCount: Long = cache.stats().hitCount()
  override def missCount: Long = cache.stats().missCount()
}

case class CaffeineCacheParam(initialCapacity: Option[Int],
                              maxSize: Option[Int],
                              maxWeightParam: Option[MaxWeightParam],
                              expiryTimeInSeconds: Option[Int]) {
  require(initialCapacity.forall(_> 0), "cache initial capacity can't be 0")
  require(maxSize.isEmpty || maxWeightParam.isEmpty, "cache max size and max weight both can't be set")
  require(maxSize.forall(_> 0), "cache max size can't be 0")
  require(maxWeightParam.map(_.maxWeight).forall(_ > 0), "cache max weight can't be 0")
}
