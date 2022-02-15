package com.evernym.verity.actor.agent.msgrouter

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.event.LoggingReceive
import com.evernym.verity.actor.agent.msgrouter.legacy.LegacyGetRoute
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, RouteSet}
import com.evernym.verity.config.{AppConfig, ConfigConstants}
import com.evernym.verity.constants.ActorNameConstants._

import scala.concurrent.ExecutionContext

/**
 * stores only one route mapping per actor
 *
 * @param appConfig application config
 */
class Route(executionContext: ExecutionContext)(implicit val appConfig: AppConfig)
  extends BasePersistentActor {

  override def futureExecutionContext: ExecutionContext = executionContext

  override val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case _: StoreRoute
      if route.isDefined => sender ! RouteAlreadySet(entityId)

    case sr: StoreRoute =>
      writeApplyAndSendItBack(RouteSet(sr.actorAddressDetail.actorTypeId, sr.actorAddressDetail.address))

    case GetStoredRoute => handleGetRoute()
  }

  override val receiveEvent: Receive = {
    case rs: RouteSet =>
      //generally there should be only one event in this actor.
      // but as part of 'v1 to v2 migration', depends on how many time
      // the same enterprise agent is migrated to a unique VAS agents,
      // that many events may be persisted and hence in below logic,
      // we are just updating the route with latest recovered events
      route = Option(ActorAddressDetail(rs.actorTypeId, rs.address))
  }

  def handleGetRoute(): Unit = {
    logger.debug("current route value: " + route)
    if (route.isDefined) {
      sender ! route
    } else {
      //this logic needs to be there until all the legacy agent routes are migrated
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
  def props(executionContext: ExecutionContext)(implicit appConfig: AppConfig): Props = Props(new Route(executionContext))
  val defaultPassivationTimeout = 600
}


//cmds
case class StoreRoute(actorAddressDetail: ActorAddressDetail) extends ActorMessage
case object GetStoredRoute extends ActorMessage