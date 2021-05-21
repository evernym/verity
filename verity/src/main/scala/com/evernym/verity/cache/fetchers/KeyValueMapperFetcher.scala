package com.evernym.verity.cache.fetchers

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.cluster_singleton.{ForKeyValueMapper, GetValue}
import com.evernym.verity.cache.KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER
import com.evernym.verity.cache.base.{FetcherParam, KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.util.Util._

import scala.concurrent.Future

class KeyValueMapperFetcher(val as: ActorSystem, val appConfig: AppConfig) extends AsyncCacheValueFetcher {

  lazy val fetcherParam: FetcherParam = KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER
  lazy val cacheConfigPath: Option[String] = Option(KEY_VALUE_MAPPER_CACHE)

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  override lazy val defaultExpiryTimeInSeconds: Option[Int] = Option(300)

  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, as)(appConfig)


  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map(kd => KeyMapping(kd, kd.key.toString, kd.key.toString))
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, AnyRef]] = {
    val gdFut = singletonParentProxyActor ? ForKeyValueMapper(GetValue(kd.key.toString))
    gdFut map {
      case Some(v: String) => Map(kd.key.toString -> v)
      case None => Map.empty
      case e => throw buildUnexpectedResponse(e)
    }
  }
}

case class KeyValue(k: String, v: String)