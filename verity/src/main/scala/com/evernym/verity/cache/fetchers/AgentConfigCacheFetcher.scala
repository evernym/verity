package com.evernym.verity.cache.fetchers

import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.{AgentConfigs, GetConfigs}
import com.evernym.verity.cache.AGENT_ACTOR_CONFIG_CACHE_FETCHER
import com.evernym.verity.cache.base.{CacheRequest, FetcherParam, ReqParam, RespParam}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.AGENT_CONFIG_CACHE
import com.evernym.verity.did.DidStr

import scala.concurrent.{ExecutionContext, Future}


class AgentConfigCacheFetcher(val agentMsgRouter: AgentMsgRouter,
                              val appConfig: AppConfig,
                              executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher{

  override def futureExecutionContext: ExecutionContext = executionContext
  private implicit val executionContextImpl: ExecutionContext = executionContext

  lazy val fetcherParam: FetcherParam = AGENT_ACTOR_CONFIG_CACHE_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(AGENT_CONFIG_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(300)

  override def toCacheRequests(rp: ReqParam): Set[CacheRequest] = {
    val gccp = rp.cmdAs[GetConfigCacheParam]
    gccp.gc.names.map(confName => CacheRequest(rp, confName, gccp.agentDID + "-" + confName))
  }

  override def getByRequest(cr: CacheRequest): Future[Option[RespParam]] = {
    val gcp = cr.reqParam.cmdAs[GetConfigCacheParam]
    val confFutResp = agentMsgRouter.execute(InternalMsgRouteParam(gcp.agentDID, gcp.gc))
    confFutResp map {
      case ac: AgentConfigs if ac.configs.nonEmpty =>
        ac.configs.find(_.name == cr.respKey).map(cd => RespParam(cd.value))
      case _: AgentConfigs =>
        None
      case x => throw buildUnexpectedResponse(x)
    }
  }
}

case class GetConfigCacheParam(agentDID: DidStr, gc: GetConfigs)