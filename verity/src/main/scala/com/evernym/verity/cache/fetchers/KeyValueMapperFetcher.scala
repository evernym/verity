package com.evernym.verity.cache.fetchers

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.actor.cluster_singleton.{ForKeyValueMapper, GetValue}
import com.evernym.verity.cache.base.{KeyDetail, KeyMapping}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.util.Util._

import scala.concurrent.Future


case class KeyValue(k: String, v: String)


class KeyValueMapperFetcher(val as: ActorSystem, appConfig: AppConfig) extends AsyncCacheValueFetcher {

  implicit val timeout: Timeout = buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS, DEFAULT_GENERAL_ASK_TIMEOUT_IN_SECONDS)

  lazy val id: Int = KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID

  //time to live in seconds, afterwards they will be considered as expired and re-fetched from source
  lazy val expiryTimeInSeconds: Option[Int] = Option(appConfig.getConfigIntOption(KEY_VALUE_MAPPER_CACHE_EXPIRATION_TIME_IN_SECONDS).getOrElse(300))
  lazy val maxSize: Option[Int] = appConfig.getConfigIntOption(KEY_VALUE_MAPPER_CACHE_MAX_SIZE)

  lazy val singletonParentProxyActor: ActorRef = getActorRefFromSelection(SINGLETON_PARENT_PROXY, as)(appConfig)


  override def toKeyDetailMappings(keyDetails: Set[KeyDetail]): Set[KeyMapping] = {
    keyDetails.map(kd => KeyMapping(kd, kd.key.toString, kd.key.toString))
  }

  override def getByKeyDetail(kd: KeyDetail): Future[Map[String, Any]] = {
    val gdFut = singletonParentProxyActor ? ForKeyValueMapper(GetValue(kd.key.toString))
    gdFut map {
      case Some(v: String) => Map(kd.key.toString -> v)
      case None => Map.empty
      case e => throw buildUnexpectedResponse(e)
    }
  }
}