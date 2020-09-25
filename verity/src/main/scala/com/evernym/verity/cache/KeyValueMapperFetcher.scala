package com.evernym.verity.cache

import akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.evernym.verity.constants.Constants._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.config.AppConfig
import com.evernym.verity.util.Util._
import com.evernym.verity.config.CommonConfig._

import scala.concurrent.Future
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.cluster_singleton.{ForKeyValueMapper, GetValue}


case class KeyValue(k: String, v: String)


class KeyValueMapperFetcher(val as: ActorSystem, appConfig: AppConfig) extends AsyncCacheValueFetcher {

  implicit val timeout: Timeout = buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ASK_TIMEOUT_IN_SECONDS)

  lazy val id: Int = KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  lazy val ttls: Option[Int] = Option(appConfig.getConfigIntOption(KEY_VALUE_MAPPER_CACHE_EXPIRATION_TIME_IN_SECONDS).getOrElse(300))

  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, as)(appConfig)


  override def getKeyDetailMapping(kds: Set[KeyDetail]): Set[KeyMapping] = {
    kds.map(kd => KeyMapping(kd, kd.key, kd.key))
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[Any, Any]] = {
    val gdFut = singletonParentProxyActor ? ForKeyValueMapper(GetValue(kd.key.toString))
    gdFut map {
      case Some(v: String) => Map(kd.key.toString -> v)
      case None => Map.empty
      case e => throw buildUnexpectedResponse(e)
    }
  }
}