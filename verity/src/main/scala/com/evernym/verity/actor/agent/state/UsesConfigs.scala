package com.evernym.verity.actor.agent.state

import java.time.ZonedDateTime
import com.evernym.verity.Exceptions.InternalServerErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.getUnhandledError
import com.evernym.verity.actor.agent.user.{AgentConfig, AgentConfigs, GetConfigDetail, GetConfigs}
import com.evernym.verity.agentmsg.msgfamily.ConfigDetail
import com.evernym.verity.cache.AGENT_ACTOR_CONFIG_CACHE_FETCHER
import com.evernym.verity.cache.base.{Cache, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.GetConfigCacheParam
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.protocol.protocols.HasAppConfig

import scala.concurrent.Future

/**
 * supposed to be used from a pairwise actor (UserAgentPairwise actor)
 * it contains utility functions to get configs from agent actor (UserAgent actor) which has the config state
 */
trait UsesConfigs extends HasAppConfig {

  def ownerAgentKeyDIDReq: DID
  def agentConfigs: Map[String, AgentConfig]
  def agentCache: Cache

  def getConfigs(names: Set[String]): Future[AgentConfigs] = {
    val gcs = names.map(n => GetConfigDetail(n, req=false))
    getMissingConfigsFromUserAgent(gcs).mapTo[AgentConfigs]
  }

  def getAgentConfigDetails: Set[ConfigDetail] = (agentConfigs ++
    getCachedConfigDetails).map(c => ConfigDetail(c._1, c._2.value)).toSet

  def getAgentConfigs(getConfDetail: Set[GetConfigDetail]): Future[AgentConfigs] = {
    val requestedConfigNames = getConfDetail.map(_.name)
    val configsFound = getAgentConfigDetails.filter(c => requestedConfigNames.contains(c.name))
    val configsNotFound = requestedConfigNames.diff(configsFound.map(_.name))
    if (configsNotFound.isEmpty) {
      Future.successful(AgentConfigs(configsFound))
    } else {
      getMissingConfigsFromUserAgent(getConfDetail.filter(gcd => configsNotFound.contains(gcd.name))) map {
        case fc: AgentConfigs => AgentConfigs(configsFound ++ fc.configs)
        case x =>
          val sd = getUnhandledError(x)
          throw new InternalServerErrorException(sd.statusCode, Option(sd.statusMsg))
      }
    }
  }

  def getCachedConfigDetails: Map[String, AgentConfig] = {
    agentCache.getCachedObjectsByFetcherId(AGENT_ACTOR_CONFIG_CACHE_FETCHER).
      map(co => co._1 -> AgentConfig(co._2.toString, ZonedDateTime.now()))
  }

  def getMissingConfigsFromUserAgent(configs: Set[GetConfigDetail]): Future[AgentConfigs] = {

    def buildKeyDetails(gcd: Set[GetConfigDetail], req: Boolean): Set[KeyDetail] = {
      if (gcd.nonEmpty) Set(KeyDetail(GetConfigCacheParam(ownerAgentKeyDIDReq, GetConfigs(gcd.map(_.name))), required = req))
      else Set.empty[KeyDetail]
    }

    val (reqs, nonReqs) = configs.partition(_.req)
    val reqsKds = buildKeyDetails(reqs, req = true)
    val nonReqsKds = buildKeyDetails(nonReqs, req = false)
    val allKeyDetails = reqsKds ++ nonReqsKds
    val gcp = GetCachedObjectParam(allKeyDetails, AGENT_ACTOR_CONFIG_CACHE_FETCHER)
    agentCache.getByParamAsync(gcp).map { cqr =>
      cqr.getConfigs
    }
  }

}
