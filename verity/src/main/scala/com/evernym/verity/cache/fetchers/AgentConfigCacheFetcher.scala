package com.evernym.verity.cache.fetchers

import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.{AgentConfigs, GetConfigs}
import com.evernym.verity.cache.AGENT_ACTOR_CONFIG_CACHE_FETCHER
import com.evernym.verity.cache.base.{FetcherParam, KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants.AGENT_CONFIG_CACHE
import com.evernym.verity.did.DidStr

import scala.concurrent.{ExecutionContext, Future}


class AgentConfigCacheFetcher(val agentMsgRouter: AgentMsgRouter,
                              val appConfig: AppConfig,
                              executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher{

  override def futureExecutionContext: ExecutionContext = executionContext
  private implicit val executionContextImplc: ExecutionContext = executionContext

  lazy val fetcherParam: FetcherParam = AGENT_ACTOR_CONFIG_CACHE_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(AGENT_CONFIG_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(300)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.flatMap { kd =>
      val gccp = kd.keyAs[GetConfigCacheParam]
      gccp.gc.names.map(n => KeyMapping(kd, gccp.agentDID + "-" + n, n))
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val gcp = kd.keyAs[GetConfigCacheParam]
    val confFutResp = agentMsgRouter.execute(InternalMsgRouteParam(gcp.agentDID, gcp.gc))
    confFutResp map {
      case fc: AgentConfigs => fc.configs.map(c => c.name -> c.value).toMap
      case x => throw buildUnexpectedResponse(x)
    }
  }
}

case class GetConfigCacheParam(agentDID: DidStr, gc: GetConfigs)