package com.evernym.verity.actor.agent.agency

import com.evernym.verity.constants.Constants.{AGENCY_DID_KEY, KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID}
import com.evernym.verity.cache.{Cache, CacheQueryResponse, GetCachedObjectParam, KeyDetail}

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext

trait AgencyIdUtil {

  def getAgencyDID(implicit generalCache: Cache): Future[String] = {
    val gcop = GetCachedObjectParam(Set(KeyDetail(AGENCY_DID_KEY, required = false)), KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID)
    generalCache.getByParamAsync(gcop).mapTo[CacheQueryResponse].map { cqr =>
      cqr.getAgencyDIDReq
    }
  }
}
