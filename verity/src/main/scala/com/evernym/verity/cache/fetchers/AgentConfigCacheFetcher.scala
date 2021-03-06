package com.evernym.verity.cache.fetchers

import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.{AgentConfigs, GetConfigs}
import com.evernym.verity.cache.base.{KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig.AGENT_CONFIG_CACHE
import com.evernym.verity.constants.Constants._
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future


class AgentConfigCacheFetcher(val agentMsgRouter: AgentMsgRouter, val appConfig: AppConfig) extends AsyncCacheValueFetcher {

  lazy val id: Int = AGENT_ACTOR_CONFIG_CACHE_FETCHER_ID
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

case class GetConfigCacheParam(agentDID: DID, gc: GetConfigs)