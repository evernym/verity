package com.evernym.verity.actor.agent.msgrouter

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.event.LoggingReceive
import com.evernym.verity.util2.RouteId
import com.evernym.verity.actor.agent.msgrouter.legacy.LegacyGetRoute
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, RouteSet}
import com.evernym.verity.config.{AppConfig, ConfigConstants}
import com.evernym.verity.constants.ActorNameConstants._

/**
 * stores only one route mapping per actor
 *
 * @param appConfig application config
 */
class Route(implicit val appConfig: AppConfig)
  extends BasePersistentActor {

  override val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case _: StoreRoute | _: StoreFromLegacy
      if route.isDefined => sender ! RouteAlreadySet(entityId)

    case sr: StoreRoute =>
      writeApplyAndSendItBack(RouteSet(sr.actorAddressDetail.actorTypeId, sr.actorAddressDetail.address))

    case sr: StoreFromLegacy =>
      writeAndApply(RouteSet(sr.actorAddressDetail.actorTypeId, sr.actorAddressDetail.address))
      sender ! Migrated(entityId)

    case GetStoredRoute => handleGetRoute()

  }

  override val receiveEvent: Receive = {
    case rs: RouteSet => route = Option(ActorAddressDetail(rs.actorTypeId, rs.address))
  }

  def handleGetRoute(): Unit = {
    logger.debug("current route value: " + route)
    if (route.isDefined) {
      sender ! route
    } else {
      val bucketId = RoutingAgentUtil.getBucketEntityId(entityId)
      val legacyGetRouteReq = LegacyGetRoute(entityId)
      legacyRouteStoreActorRegion forward ForIdentifier(bucketId, legacyGetRouteReq)
    }
  }

  var route: Option[ActorAddressDetail] = None

  val legacyRouteStoreActorRegion: ActorRef = ClusterSharding(context.system).shardRegion(LEGACY_AGENT_ROUTE_STORE_REGION_ACTOR_NAME)

  override lazy val persistenceEncryptionKey: String = appConfig.getStringReq(ConfigConstants.SECRET_ROUTING_AGENT)
}

object Route {
  def props(implicit appConfig: AppConfig): Props = Props(new Route)
}


//cmds
case class StoreRoute(actorAddressDetail: ActorAddressDetail) extends ActorMessage
case object GetStoredRoute extends ActorMessage

case class StoreFromLegacy(actorAddressDetail: ActorAddressDetail) extends ActorMessage
case class Migrated(route: RouteId) extends ActorMessage
