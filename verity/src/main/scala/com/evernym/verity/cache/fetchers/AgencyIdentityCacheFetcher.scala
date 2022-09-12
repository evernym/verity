package com.evernym.verity.cache.fetchers

import com.evernym.verity.actor.agent.agency.{AgencyInfo, GetAgencyIdentity}
import com.evernym.verity.actor.agent.msgrouter._
import com.evernym.verity.cache.AGENCY_IDENTITY_CACHE_FETCHER
import com.evernym.verity.cache.base.{CacheRequest, CacheKey, FetcherParam, ReqParam, RespParam}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.did.DidStr

import scala.concurrent.{ExecutionContext, Future}

//this cache saves an actor round trip
class AgencyIdentityCacheFetcher(val agentMsgRouter: AgentMsgRouter,
                                 val appConfig: AppConfig,
                                 executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher{

  override def futureExecutionContext: ExecutionContext = executionContext
  private implicit val executionContextImpl: ExecutionContext = executionContext

  lazy val fetcherParam: FetcherParam = AGENCY_IDENTITY_CACHE_FETCHER

  lazy val cacheConfigPath: Option[String] = Option(AGENCY_DETAIL_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(1800)

  override def toCacheRequests(rp: ReqParam): Set[CacheRequest] = {
    val gadcp = rp.cmdAs[GetAgencyIdentityCacheParam]
    Set(CacheRequest(rp, gadcp.gad.did, buildCacheKey(gadcp.gad)))
  }

  override def getByRequest(cr: CacheRequest): Future[Option[RespParam]] = {
    val gadcp = cr.reqParam.cmdAs[GetAgencyIdentityCacheParam]
    val gadFutResp = agentMsgRouter.execute(InternalMsgRouteParam(gadcp.localAgencyDID, gadcp.gad))
    gadFutResp.map {
      case ai: AgencyInfo if ! ai.isErrorFetchingAnyData =>
        logger.info(s"agency info received from source for '${gadcp.gad.did}': " + ai)
        Option(RespParam(ai))
      case ai: AgencyInfo if ai.verKeyErrorOpt.isDefined =>
        throw buildUnexpectedResponse(ai.verKeyErrorOpt.get)
      case ai: AgencyInfo if ai.endpointErrorOpt.isDefined =>
        throw buildUnexpectedResponse(ai.endpointErrorOpt.get)
      case x => throw buildUnexpectedResponse(x)
    }
  }

  private def buildCacheKey(gad: GetAgencyIdentity): CacheKey = s"${gad.did}:${gad.getVerKey}:${gad.getEndpoint}"
}

case class GetAgencyIdentityCacheParam(localAgencyDID: DidStr, gad: GetAgencyIdentity)