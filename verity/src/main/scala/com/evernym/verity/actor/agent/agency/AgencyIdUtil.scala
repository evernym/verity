package com.evernym.verity.actor.agent.agency

import com.evernym.verity.util2.HasExecutionContextProvider
import com.evernym.verity.constants.Constants.AGENCY_DID_KEY

import scala.concurrent.{ExecutionContext, Future}
import com.evernym.verity.cache.KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER
import com.evernym.verity.cache.base.{Cache, GetCachedObjectParam, ReqParam}

trait AgencyIdUtil
  extends HasExecutionContextProvider {

  private implicit val executionContext: ExecutionContext = futureExecutionContext

  def getAgencyDID(implicit generalCache: Cache): Future[String] = {
    val gcop = GetCachedObjectParam(ReqParam(AGENCY_DID_KEY, required = false), KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER)
    generalCache.getByParamAsync(gcop).map { cqr =>
      cqr.getAgencyDIDReq
    }
  }
}
