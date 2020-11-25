package com.evernym.verity.actor.agent.msgrouter

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ClusterSharding
import akka.event.LoggingReceive
import com.evernym.verity.actor.cluster_singleton.maintenance.{AlreadyCompleted, AlreadyRegistered, Register}
import com.evernym.verity.actor.cluster_singleton.maintenance.Registered
import com.evernym.verity.actor.persistence.BasePersistentActor
import com.evernym.verity.actor.{ActorMessageClass, ActorMessageObject, ForIdentifier, RouteSet}
import com.evernym.verity.config.{AppConfig, CommonConfig}
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.{DID, HasLogger}
import com.typesafe.scalalogging.Logger

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
  extends BasePersistentActor
    with HasLogger {

  override val receiveCmd: Receive = LoggingReceive.withLabel("receiveCmd") {
    case sr: SetRoute if routes.contains(sr.forDID) => sender ! RouteAlreadySet(sr.forDID)

    case sr: SetRoute =>
      writeApplyAndSendItBack(
        RouteSet(sr.forDID, sr.actorAddressDetail.actorTypeId, sr.actorAddressDetail.address))

    case gr: GetRoute => handleGetRoute(gr)

    case SendAllRouteRegistrationRequest => sender ! Register(entityId, getAllRouteDIDs().size)

    case grd: GetRouteBatch => handleGetRouteBatch(grd)

    case _ @ (_: Registered | AlreadyRegistered | AlreadyCompleted) => //nothing to do
  }

  override val receiveEvent: Receive = {
    case rs: RouteSet =>
      routes = routes.updated(rs.forDID, ActorAddressDetail(rs.actorTypeId, rs.address))
  }

  def getAllRouteDIDs(totalCandidates:Int = routes.size) : Set[String] = {
    routes.take(totalCandidates).keySet
  }

  def handleGetRouteBatch(grd: GetRouteBatch): Unit = {
    logger.debug(s"ASC [$persistenceId] [ASCE->ARS] received GetRouteBatch: " + grd)
    val candidates = getAllRouteDIDs(grd.totalCandidates).slice(grd.fromIndex, grd.fromIndex + grd.batchSize)
    val resp = CandidateRoutesToBeProcessed(candidates)
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

  val logger: Logger = getLoggerByClass(getClass)
  val routingAgentRegion: ActorRef = ClusterSharding(context.system).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)
  override lazy val persistenceEncryptionKey: String = appConfig.getConfigStringReq(CommonConfig.SECRET_ROUTING_AGENT)
}

object AgentRouteStore {
  def props(implicit appConfig: AppConfig): Props = Props(new AgentRouteStore)
}

case class RouteInfo(actorRef: ActorRef, entityId: String)
case class ActorAddressDetail(actorTypeId: Int, address: String) extends ActorMessageClass
case class Status(totalCandidates: Int, processedRoutes: Int) extends ActorMessageClass

//cmds
case class SetRoute(forDID: DID, actorAddressDetail: ActorAddressDetail) extends ActorMessageClass
case class GetRoute(forDID: DID, oldBucketMapperVersions: Set[String] = RoutingAgentUtil.oldBucketMapperVersionIds) extends ActorMessageClass
case class GetRouteBatch(totalCandidates: Int, fromIndex: Int, batchSize: Int) extends ActorMessageClass
case object SendAllRouteRegistrationRequest extends ActorMessageObject

//response msgs
case class RouteAlreadySet(forDID: DID) extends ActorMessageClass
case class CandidateRoutesToBeProcessed(dids: Set[DID]) extends ActorMessageClass
