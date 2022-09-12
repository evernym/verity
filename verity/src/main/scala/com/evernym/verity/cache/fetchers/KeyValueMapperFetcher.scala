package com.evernym.verity.cache.fetchers

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import com.evernym.verity.actor.cluster_singleton.{ForKeyValueMapper, GetValue}
import com.evernym.verity.cache.KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER
import com.evernym.verity.cache.base.{CacheRequest, FetcherParam, ReqParam, RespParam}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.ConfigConstants._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.util.Util._

import scala.concurrent.{ExecutionContext, Future}

class KeyValueMapperFetcher(val as: ActorSystem,
                            val appConfig: AppConfig,
                            executionContext: ExecutionContext)
  extends AsyncCacheValueFetcher {

  override def futureExecutionContext: ExecutionContext = executionContext
  private implicit val executionContextImplc: ExecutionContext = executionContext


  lazy val fetcherParam: FetcherParam = KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(KEY_VALUE_MAPPER_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(300)

  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, as)(appConfig)


  override def toCacheRequests(rp: ReqParam): Set[CacheRequest] = {
    Set(CacheRequest(rp, rp.cmd.toString, rp.cmd.toString))
  }

  override def getByRequest(cr: CacheRequest): Future[Option[RespParam]] = {
    val gdFut = singletonParentProxyActor ? ForKeyValueMapper(GetValue(cr.respKey))
    gdFut map {
      case Some(v: String) => Option(RespParam(v))
      case None => None
      case e => throw buildUnexpectedResponse(e)
    }
  }
}