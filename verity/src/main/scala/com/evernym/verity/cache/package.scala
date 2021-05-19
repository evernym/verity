package com.evernym.verity

import com.evernym.verity.cache.base.FetcherParam

package object cache {

  val KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER = FetcherParam(1, "key-value-mapper")
  val AGENCY_IDENTITY_CACHE_FETCHER = FetcherParam(2, "agency-identity")
  val WALLET_VER_KEY_CACHE_FETCHER = FetcherParam(3, "wallet-ver-key")
  val AGENT_ACTOR_CONFIG_CACHE_FETCHER = FetcherParam(4, "agent-actor-config")
  val ROUTING_DETAIL_CACHE_FETCHER = FetcherParam(5, "routing-detail")
  val LEDGER_GET_ENDPOINT_CACHE_FETCHER = FetcherParam(6, "ledger-get-endpoint")
  val LEDGER_GET_VER_KEY_CACHE_FETCHER = FetcherParam(7, "ledger-get-ver-key")
  val LEDGER_GET_SCHEMA_FETCHER = FetcherParam(8, "ledger-get-schema")
  val LEDGER_GET_CRED_DEF_FETCHER = FetcherParam(9, "ledger-get-cred-def")
}
