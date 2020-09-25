package com.evernym.verity.cache

import com.evernym.verity.constants.Constants._
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.Status.DATA_NOT_FOUND
import com.evernym.verity.actor.agent.msgrouter.{AgentMsgRouter, InternalMsgRouteParam}
import com.evernym.verity.actor.agent.user.{AgentConfigs, GetConfigs}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.protocol.engine.DID

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext


case class GetConfigCacheParam(agentDID: DID, gc: GetConfigs)


class AgentConfigCacheFetcher(val agentMsgRouter: AgentMsgRouter, config: AppConfig) extends AsyncCacheValueFetcher {

  lazy val id: Int = AGENT_ACTOR_CONFIG_CACHE_FETCHER_ID

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  lazy val ttls: Option[Int] = Option(config.getConfigIntOption(AGENT_CONFIG_CACHE_EXPIRATION_TIME_IN_SECONDS).getOrElse(300))

  override def getKeyDetailMapping(kds: Set[KeyDetail]): Set[KeyMapping] = {
    kds.flatMap { kd =>
      val gccp = kd.key.asInstanceOf[GetConfigCacheParam]
      gccp.gc.names.map(n => KeyMapping(kd, gccp.agentDID + "-" + n, n))
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[Any, Any]] = {
    val gcp = kd.key.asInstanceOf[GetConfigCacheParam]
    val confFutResp = agentMsgRouter.execute(InternalMsgRouteParam(gcp.agentDID, gcp.gc))
    confFutResp map {
      case fc: AgentConfigs => fc.configs.map(c => c.name -> c.value).toMap
      case x => throw buildUnexpectedResponse(x)
    }
  }

  override def throwRequiredKeysNotFoundException(reqKeysNotFound: Set[Any]): HandledErrorException = {
    throw new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option("required configs not yet set: " + reqKeysNotFound.mkString(", ")))
  }
}
