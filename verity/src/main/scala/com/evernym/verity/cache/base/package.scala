package com.evernym.verity.cache

package object base {
  val DEFAULT_MAX_CACHE_SIZE = 30

  type CacheKey = String //used by the cache for storage/lookup
  type RespKey = String //used to return the response key (to be used by the cache client code)
}
