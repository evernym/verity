package com.evernym.verity.actor.agent.msgrouter

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.cluster.sharding.ShardRegion.EntityId
import akka.event.LoggingReceive
import com.evernym.verity.actor.agent.maintenance.{AlreadyCompleted, AlreadyRegistered, RegisteredRouteSummary}
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.actor.{ActorMessage, ForIdentifier, Registered, RouteSet}
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.protocol.engine.DID


/**
 * stores agent routing details (it DOESN'T do any message routing itself)
 * this is used as a sharded actor and one actor instance stores more than one routes
 *
 * see 'RoutingAgentBucketMapperV1' to know how it is decided for which DID
 * it will go to which actor instance (either for store or get)
 *
 * @param appConfig application config
 */
class AgentRouteStore(implicit val appConfig: AppConfig)
  extends BasePersistentActor {

  override val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case sr: SetRoute if routes.contains(sr.forDID) => sender ! RouteAlreadySet(sr.forDID)

    case sr: SetRoute =>
      writeApplyAndSendItBack(
        RouteSet(sr.forDID, sr.actorAddressDetail.actorTypeId, sr.actorAddressDetail.address))

    case gr: GetRoute => handleGetRoute(gr)

    case GetRegisteredRouteSummary => sender ! RegisteredRouteSummary(entityId, getAllRouteDIDs().size)

    case grd: GetRouteBatch => handleGetRouteBatch(grd)

    case _ @ (_: Registered | AlreadyRegistered | AlreadyCompleted) => //nothing to do
  }

  override val receiveEvent: Receive = {
    case rs: RouteSet =>
      val aad = ActorAddressDetail(rs.actorTypeId, rs.address)
      routes = routes.updated(rs.forDID, aad)
      routesByInsertionOrder = routesByInsertionOrder :+ (rs.forDID, aad.actorTypeId)
  }

  var routesByInsertionOrder: List[(DID, Int)] = List.empty

  def getAllRouteDIDs(totalCandidates:Int = routesByInsertionOrder.size,
                      actorTypeIds: List[Int] = List.empty): Set[String] = {
    routesByInsertionOrder
      .take(totalCandidates)
      .filter(r => actorTypeIds.isEmpty || actorTypeIds.contains(r._2))
      .map(_._1)
      .toSet
  }

  def handleGetRouteBatch(grd: GetRouteBatch): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCE->ARS] received GetRouteBatch: " + grd)
    val candidates =
      getAllRouteDIDs(grd.totalCandidates, grd.actorTypeIds)
      .slice(grd.fromIndex, grd.fromIndex + grd.batchSize)
    val resp = GetRouteBatchResult(entityId, candidates)
    logger.debug(s"ASC [$persistenceId] sending response: " + resp)
    sender ! resp
  }

  def handleGetRoute(gr: GetRoute): Unit = {
    val sndr = sender()
    val ri = routes.get(gr.forDID)
    logger.debug("get route result: " + ri)
    if (ri.isDefined || gr.oldBucketMapperVersions.isEmpty) {
      sndr ! ri
    } else {
      //NOTE: based on current use case, this block of code won't be used.

      //this section should be only executed if there has been old bucket mapper strategy used and then
      //it was decided to use a new strategy and we want to make sure to lookup old bucket/actor
      val nextBucketPersistenceId = RoutingAgentUtil.getBucketPersistenceId(gr.forDID, gr.oldBucketMapperVersions.head)
      val newGr = GetRoute(gr.forDID, gr.oldBucketMapperVersions.tail)
      routingAgentRegion tell(ForIdentifier(nextBucketPersistenceId, newGr), sndr)
    }
  }

  var routes: Map[String, ActorAddressDetail] = Map.empty

  val routingAgentRegion: ActorRef = ClusterSharding(context.system).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)
  override lazy val persistenceEncryptionKey: String = appConfig.getConfigStringReq(CommonConfig.SECRET_ROUTING_AGENT)
}

object AgentRouteStore {
  def props(implicit appConfig: AppConfig): Props = Props(new AgentRouteStore)
}

case class RouteInfo(actorRef: ActorRef, entityId: String)
case class ActorAddressDetail(actorTypeId: Int, address: String) extends ActorMessage
case class Status(totalCandidates: Int, processedRoutes: Int) extends ActorMessage

//cmds
case class SetRoute(forDID: DID, actorAddressDetail: ActorAddressDetail) extends ActorMessage
case class GetRoute(forDID: DID, oldBucketMapperVersions: Set[String] = RoutingAgentUtil.oldBucketMapperVersionIds)
  extends ActorMessage
case class GetRouteBatch(totalCandidates: Int,
                         fromIndex: Int,
                         batchSize: Int,
                         actorTypeIds: List[Int] = List.empty) extends ActorMessage
case object GetRegisteredRouteSummary extends ActorMessage

//response msgs
case class RouteAlreadySet(forDID: DID) extends ActorMessage
case class GetRouteBatchResult(routeStoreEntityId: EntityId, dids: Set[DID]) extends ActorMessage
