package com.evernym.verity.cache.fetchers

import com.evernym.verity.actor.agent.agency.{AgencyInfo, GetAgencyIdentity}
import com.evernym.verity.actor.agent.msgrouter._
import com.evernym.verity.cache.AGENCY_IDENTITY_CACHE_FETCHER
import com.evernym.verity.cache.base.{FetcherParam, KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.{ExecutionContext, Future}

//this cache saves an actor round trip
class AgencyIdentityCacheFetcher(val agentMsgRouter: AgentMsgRouter, val appConfig: AppConfig, executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher{

  override def futureExecutionContext: ExecutionContext = executionContext
  private implicit val executionContextImplc: ExecutionContext = executionContext

  lazy val fetcherParam: FetcherParam = AGENCY_IDENTITY_CACHE_FETCHER

  lazy val cacheConfigPath: Option[String] = Option(AGENCY_DETAIL_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(1800)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gadcp = kd.keyAs[GetAgencyIdentityCacheParam]
      KeyMapping(kd, gadcp.gad.did, gadcp.gad.did)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val gadcp = kd.keyAs[GetAgencyIdentityCacheParam]
    val gadFutResp = agentMsgRouter.execute(InternalMsgRouteParam(gadcp.localAgencyDID, gadcp.gad))
    gadFutResp.map {
      case ai: AgencyInfo if ! ai.isErrorFetchingAnyData => Map(gadcp.gad.did -> ai)
      case ai: AgencyInfo if ai.verKeyErrorOpt.isDefined =>
        throw buildUnexpectedResponse(ai.verKeyErrorOpt.get)
      case ai: AgencyInfo if ai.endpointErrorOpt.isDefined =>
        throw buildUnexpectedResponse(ai.endpointErrorOpt.get)
      case x => throw buildUnexpectedResponse(x)
    }
  }
}

case class GetAgencyIdentityCacheParam(localAgencyDID: DID, gad: GetAgencyIdentity)