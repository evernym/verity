package com.evernym.verity.actor.agent.msgrouter


import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.ask
import akka.util.Timeout
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.Exceptions.{BadRequestErrorException, InvalidValueException}
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.RouteTo
import com.evernym.verity.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.EntityTypeMapper
import com.evernym.verity.actor.agent.msghandler.incoming.{ProcessPackedMsg, ProcessRestMsg}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.cache.base.{Cache, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.cache.fetchers.{CacheValueFetcher, RoutingDetailCacheFetcher}
import com.evernym.verity.config.AppConfig
import com.evernym.verity.config.CommonConfig._
import com.evernym.verity.constants.LogKeyConstants._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.util.LogUtil.logDuration
import com.evernym.verity.util.Util._
import com.evernym.verity.util.{Base58Util, ReqMsgContext, RestMsgContext}
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * performs agent message routing
 * @param appConfig application config
 * @param system actor system
 */
class AgentMsgRouter(implicit val appConfig: AppConfig, val system: ActorSystem)
  extends ShardRegionNameFromActorSystem
   with HasLegacyRegionNames {

  //TODO: need to remove this actorSystem if we can just use the system parameter supplied in constructor
  override def actorSystem: ActorSystem = system

  implicit lazy val timeout: Timeout = buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS,
    DEFAULT_GENERAL_ASK_TIMEOUT_IN_SECONDS)

  val logger: Logger = getLoggerByClass(classOf[AgentMsgRouter])

  lazy val fetchers: Map[Int, CacheValueFetcher] = Map (
    ROUTING_DETAIL_CACHE_FETCHER_ID -> new RoutingDetailCacheFetcher(system, appConfig)
  )
  lazy val routingCache: Cache = new Cache("RC", fetchers)

  lazy val agencyAgentRegion: ActorRef = ClusterSharding(system).shardRegion(AGENCY_AGENT_REGION_ACTOR_NAME)
  lazy val agencyAgentPairwiseRegion: ActorRef = ClusterSharding(system).shardRegion(AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME)
  lazy val routingAgentRegion: ActorRef = ClusterSharding(system).shardRegion(AGENT_ROUTE_STORE_REGION_ACTOR_NAME)

  private def setRoute(sr: SetRoute): Future[Any] = {
    val entityId = RoutingAgentUtil.getBucketEntityId(sr.forDID)
    routingAgentRegion ? ForIdentifier(entityId, sr)
  }

  private def getRouteInfoViaCache(gr: GetRoute): Future[Option[ActorAddressDetail]] = {
    val gcop = GetCachedObjectParam(KeyDetail(gr, required = false), ROUTING_DETAIL_CACHE_FETCHER_ID)
    routingCache.getByParamAsync(gcop).map { cqr =>
      cqr.getActorAddressDetailOpt(gr.forDID)
    }
  }

  private def getRouteInfo(route: RouteTo): Future[Option[ActorAddressDetail]] = {
    val forDID = AgentMsgRouter.getDIDForRoute(route) match {
      case Success(did) => did
      case Failure(e) =>
        logger.error(s"Could not extract DID for route: $route")
        throw e
    }
    val startTime = LocalDateTime.now
    logger.debug("get route info started", (LOG_KEY_SRC_DID, forDID))
    val gr = GetRoute(forDID, RoutingAgentUtil.oldBucketMapperVersionIds)
    val futRes = getRouteInfoViaCache(gr)
    futRes map {
      case Some(aa: ActorAddressDetail) => Some(aa)
      case None => None
    } map { r =>
      val curTime = LocalDateTime.now
      val millis = ChronoUnit.MILLIS.between(startTime, curTime)
      logger.debug(s"get route info finished, time taken (in millis): $millis", (LOG_KEY_SRC_DID, forDID))
      r
    }
  }

  def getRouteRegionActor(route: RouteTo): Future[RouteInfo] = {
    getRouteInfo(route).map {
      case Some(ad: ActorAddressDetail) =>
        val regionActor: ActorRef = getActorTypeToRegions(ad.actorTypeId)
        val ri = RouteInfo(regionActor, ad.address)
        logger.debug("routing info for route '" + route + "' is: " + ri, (LOG_KEY_SRC_DID, route))
        ri
      case None =>
        throw new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode, msg = Option(s"agent not created for route: $route"))
    }
  }

  private def sendCmdToGivenActor(sender: ActorRef, cmd: Any, to: ActorRef): Future[Any] = {
    val futResp = to ? cmd
    futResp map { r =>
      sender ! r
    }
  }

  private def routePackedMsg(pmrp: PackedMsgRouteParam)(implicit senderOpt: Option[ActorRef]): Future[Any] = {
    // flow diagram: fwd + ctl + proto + legacy, step 6 -- Find route to relevant actor, send inner msg.
    // As far as I can tell, what the next line does is look up the actor for a given toRoute
    // value. That value could be a verkey or an unqualified DID. I'm not sure why the concept
    // of sharding region enters into it; shouldn't this just be an actor ID that has the region
    // baked into it in some way? When I follow the function and its internals, I see what looks
    // like a mapping between actor type and regions, which doesn't seem to care about shards in
    // a cluster. ?
    getRouteRegionActor(pmrp.toRoute) flatMap { ri =>
      logDuration(logger, "sending msg to target actor") {
        logger.debug("sending msg to target actor")
        senderOpt.map { implicit sndr =>
          // The relevant actor in this case will be one of our Agent classes, in its
          // capacity as an impl of AgentIncomingMsgHandler. Most commonly it'll be
          // UserAgentPairwise, since we expect most packed messages to end up there.
          sendCmdToGivenActor(sndr, ForIdentifier(ri.entityId, ProcessPackedMsg(pmrp.packedMsg, pmrp.reqMsgContext)), ri.actorRef)
        }.getOrElse {
          ri.actorRef ? ForIdentifier(ri.entityId, ProcessPackedMsg(pmrp.packedMsg, pmrp.reqMsgContext))
        }
      }
    }
  }

  private def routeInternalMsg(imrp: InternalMsgRouteParam)(implicit senderOpt: Option[ActorRef]): Future[Any] = {
    // flow diagram: ctl + proto, step 12; sig, step 7
    getRouteRegionActor(imrp.toRoute) flatMap { ri =>
      senderOpt.map { implicit sndr =>
        sendCmdToGivenActor(sndr, ForIdentifier(ri.entityId, imrp.msg), ri.actorRef)
      }.getOrElse {
        ri.actorRef ? ForIdentifier(ri.entityId, imrp.msg)
      }
    }
  }

  private def routeRestMsg(rmrp: RestMsgRouteParam)(implicit senderOpt: Option[ActorRef]): Future[Any] = {
    // flow diagram: rest, step 7
    getRouteRegionActor(rmrp.toRoute) flatMap { ri =>
      logDuration(logger, "sending rest msg to target actor") {
        logger.debug("sending rest msg to target actor")
        senderOpt.map { implicit sndr =>
          sendCmdToGivenActor(sndr, ForIdentifier(ri.entityId, ProcessRestMsg(rmrp.msg, rmrp.restMsgContext)), ri.actorRef)
        }.getOrElse {
          ri.actorRef ? ForIdentifier(ri.entityId, ProcessRestMsg(rmrp.msg, rmrp.restMsgContext))
        }
      }
    }
  }

  private def executeCmd: PartialFunction[(Any, Option[ActorRef]), Future[Any]] = {
    case (cmd, senderOpt) =>
      cmd match {
        case sr: SetRoute => setRoute(sr)
        case gr: GetRoute => getRouteInfo(gr.forDID)
        // flow diagram: fwd, step 5 -- trigger routing for packed messages.
        case efw: PackedMsgRouteParam   => routePackedMsg(efw)(senderOpt)
        // flow diagram: ctl + proto, step 11; SIG, step 6 -- trigger routing for internal message.
        case im: InternalMsgRouteParam  => routeInternalMsg(im)(senderOpt)
        // flow diagram: rest, step 6 -- trigger routing for REST message.
        case rm: RestMsgRouteParam      => routeRestMsg(rm)(senderOpt)
      }
  }

  def execute: PartialFunction[Any, Future[Any]] = {
    case cmd: Any => executeCmd(cmd, None)
  }

  def forward: PartialFunction[(Any, ActorRef), Unit] = {
    case (cmd, sender) =>
      val futResp = executeCmd(cmd, Option(sender))
      futResp.recover {
        case e: Exception => ExceptionHandler.handleException(e, sender)
      }
  }

  protected def getActorTypeToRegions(actorTypeId: Int): ActorRef = actorTypeToRegions(actorTypeId)

  private lazy val actorTypeToRegions = EntityTypeMapper.buildRegionMappings(appConfig, actorSystem)
}

object AgentMsgRouter {
  def getDIDForRoute(route: RouteTo): Try[DID] = {
    // We support DID based routing but to support community routing we are allowing a temporary
    // hack to support verkey based routing.
    // Assumption:
    //  * Is NOT a qualified DID - should look like: e7GdJYpnbRBRN7p5Rc7mT (not did:sov:e7GdJYpnbRBRN7p5Rc7mT)
    //  * RouteTo is base58 encoded
    //  * RouteTo is either 16 bytes or 32 bytes.
    // If RouteTo is 32 bytes, we take the first 16 bytes and present it as a DID. This works
    // because DID generated by Libindy are created by first generating a verkey and then the
    // DID is derived from the first 16 bytes.
    // This hack will work currently because, one we don't support rotating keys and all DID are created
    // using libindy.
    val decodedRoute = Base58Util.decode(route)
    decodedRoute match {
      case Success(m) =>
        m.length match {
          case VALID_DID_BYTE_LENGTH => Success(route)
          case VALID_VER_KEY_BYTE_LENGTH =>
            val d = m.take(VALID_DID_BYTE_LENGTH)
            Success(Base58Util.encode(d))
          case _ =>
            val msg = s"Byte length of route should be $VALID_DID_BYTE_LENGTH or $VALID_VER_KEY_BYTE_LENGTH but was found ${m.length}"
            Failure(new InvalidValueException(Some(msg)))
        }
      case Failure(_) =>
        Failure(new InvalidValueException(Some("Route is not a base58 string")))
    }
  }
}

case class InternalMsgRouteParam(toRoute: RouteTo, msg: Any)
case class PackedMsgRouteParam(toRoute: RouteTo, packedMsg: PackedMsg, reqMsgContext: ReqMsgContext)
case class RestMsgRouteParam(toRoute: RouteTo, msg: String, restMsgContext: RestMsgContext)
