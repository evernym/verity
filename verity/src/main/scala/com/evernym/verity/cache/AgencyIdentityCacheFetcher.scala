package com.evernym.verity.cache

import com.evernym.verity.constants.Constants._
import com.evernym.verity.actor.agent.msgrouter._
import com.evernym.verity.actor.agent.agency.{AgencyInfo, GetAgencyIdentity}
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext


case class GetAgencyIdentityCacheParam(localAgencyDID: DID, gad: GetAgencyIdentity)


//this cache saves an actor round trip
class AgencyIdentityCacheFetcher(val agentMsgRouter: AgentMsgRouter, config: AppConfig) extends AsyncCacheValueFetcher {

  lazy val id: Int = AGENCY_DETAIL_CACHE_FETCHER_ID

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  lazy val ttls: Option[Int] = Option(config.getConfigIntOption(AGENCY_DETAIL_CACHE_EXPIRATION_TIME_IN_SECONDS).getOrElse(1800))

  override def getKeyDetailMapping(kds: Set[KeyDetail]): Set[KeyMapping] = {
    kds.map { kd =>
      val gadcp = kd.key.asInstanceOf[GetAgencyIdentityCacheParam]
      KeyMapping(kd, gadcp.gad.did, gadcp.gad.did)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, Any]] = {
    val gadcp = kd.key.asInstanceOf[GetAgencyIdentityCacheParam]
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
