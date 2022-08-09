package com.evernym.verity

import com.evernym.verity.cache.base.FetcherParam

package object cache {
  val KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER = FetcherParam(1, "key-value-mapper")
  val AGENCY_IDENTITY_CACHE_FETCHER = FetcherParam(2, "agency-identity")
  val AGENT_ACTOR_CONFIG_CACHE_FETCHER = FetcherParam(3, "agent-actor-config")
  val LEDGER_GET_ENDPOINT_CACHE_FETCHER = FetcherParam(4, "ledger-get-endpoint")
  val LEDGER_GET_VER_KEY_CACHE_FETCHER = FetcherParam(5, "ledger-get-ver-key")
}
