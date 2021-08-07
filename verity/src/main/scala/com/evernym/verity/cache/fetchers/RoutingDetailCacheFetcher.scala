package com.evernym.verity.cache.fetchers

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetStoredRoute}
import com.evernym.verity.cache.ROUTING_DETAIL_CACHE_FETCHER
import com.evernym.verity.cache.base.{FetcherParam, KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.ActorNameConstants._

import scala.concurrent.{ExecutionContext, Future}


class RoutingDetailCacheFetcher (val as: ActorSystem, val appConfig: AppConfig, executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher {
  override implicit def futureExecutionContext: ExecutionContext = executionContext

  lazy val fetcherParam: FetcherParam = ROUTING_DETAIL_CACHE_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(LEDGER_GET_VER_KEY_CACHE)
  lazy val routeRegion: ActorRef = ClusterSharding(as).shardRegion(ROUTE_REGION_ACTOR_NAME)

  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map { kd =>
      val routeId = kd.keyAs[String]
      KeyMapping(kd, routeId, routeId)
    }
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val routeId = kd.keyAs[String]
    val startTime = LocalDateTime.now
    val grFutResp = routeRegion ? ForIdentifier(routeId, GetStoredRoute)
    grFutResp map {
      case Some(aad: ActorAddressDetail) =>
        val curTime = LocalDateTime.now
        val millis = ChronoUnit.MILLIS.between(startTime, curTime)
        logger.debug(s"get route finished, time taken (in millis): $millis")
        Map(routeId -> aad)
      case None => Map.empty[String, AnyRef]
      case x => throw buildUnexpectedResponse(x)
    }
  }
}
