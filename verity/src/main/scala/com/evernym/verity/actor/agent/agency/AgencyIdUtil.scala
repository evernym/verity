package com.evernym.verity.actor.agent.agency

import com.evernym.verity.constants.Constants.{AGENCY_DID_KEY, KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID}

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.cache.base.{Cache, GetCachedObjectParam, KeyDetail}

trait AgencyIdUtil {

  def getAgencyDID(implicit generalCache: Cache): Future[String] = {
    val gcop = GetCachedObjectParam(KeyDetail(AGENCY_DID_KEY, required = false), KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID)
    generalCache.getByParamAsync(gcop).map { cqr =>
      cqr.getAgencyDIDReq
    }
  }
}
