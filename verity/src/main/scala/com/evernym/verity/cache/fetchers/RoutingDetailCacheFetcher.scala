package com.evernym.verity.cache.fetchers

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute, RoutingAgentUtil}
import com.evernym.verity.cache.base.{KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._

import scala.concurrent.Future


class RoutingDetailCacheFetcher (val as: ActorSystem, val appConfig: AppConfig) extends AsyncCacheValueFetcher {

  lazy val id: Int = ROUTING_DETAIL_CACHE_FETCHER_ID
  lazy val cacheConfigPath: Option[String] = Option(LEDGER_GET_VER_KEY_CACHE)
  lazy val routingAgentRegion: ActorRef = ClusterSharding(as).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val gr = kd.keyAs[GetRoute]
      KeyMapping(kd, gr.forDID, gr.forDID)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val gr = kd.keyAs[GetRoute]
    val bucketId = RoutingAgentUtil.getBucketEntityId(gr.forDID)
    val startTime = LocalDateTime.now
    val grFutResp = routingAgentRegion ? ForIdentifier(bucketId, gr)
    grFutResp map {
      case Some(aad: ActorAddressDetail) =>
        val curTime = LocalDateTime.now
        val millis = ChronoUnit.MILLIS.between(startTime, curTime)
        logger.debug(s"get route finished (for bucket id: $bucketId), time taken (in millis): $millis")
        Map(gr.forDID -> aad)
      case None => Map.empty
      case x => throw buildUnexpectedResponse(x)
    }
  }
}
