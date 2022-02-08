package com.evernym.verity.cache.providers

trait CacheProvider {

  def cachedObjects: Map[String, Any]

  def size: Int
  def hitCount: Long
  def missCount: Long

  def invalidate(key: String): Unit
  def put(key: String, value: AnyRef): Unit
  def get(key: String): Option[AnyRef]

  def stats: CacheStats = CacheStats(size, hitCount, missCount)
}

case class CacheStats(size: Int, hitCount: Long, missCount: Long) {

  def hitRatio: String = {
    try {
      val total = hitCount + missCount
      if (total > 0) {
        val per = hitCount * 100.0f / total
        f"$per%1.2f" + "%"
      } else "n/a"
    } catch {
      case e: Throwable => s"n/a (error while calculation: ${e.getMessage})"
    }
  }

  override def toString: String = s"size: $size, hitRatio: $hitRatio"
}

case class MaxWeightParam(maxWeight: Long, weigher: (String, AnyRef) => Int)
