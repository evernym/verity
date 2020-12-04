package com.evernym.verity.cache

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute, RoutingAgentUtil}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.Util._
import com.evernym.verity.config.CommonConfig._

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext


class RoutingDetailCacheFetcher (val as: ActorSystem, config: AppConfig) extends AsyncCacheValueFetcher {

  lazy val id: Int = ROUTING_DETAIL_CACHE_FETCHER_ID
  implicit val timeout: Timeout = buildTimeout(config, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ASK_TIMEOUT_IN_SECONDS)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  lazy val ttls: Option[Int] = None

  lazy val routingAgentRegion: ActorRef = ClusterSharding(as).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)

  override def getKeyDetailMapping(kds: Set[KeyDetail]): Set[KeyMapping] = {
    kds.map { kd =>
      val gr = kd.key.asInstanceOf[GetRoute]
      KeyMapping(kd, gr.forDID, gr.forDID)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, Any]] = {
    val gr = kd.key.asInstanceOf[GetRoute]
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
