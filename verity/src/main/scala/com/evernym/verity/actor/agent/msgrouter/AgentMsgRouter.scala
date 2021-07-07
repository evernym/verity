package com.evernym.verity.actor.agent.msgrouter


import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.sharding.ClusterSharding
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import com.evernym.verity.constants.ActorNameConstants._
import com.evernym.verity.constants.Constants._
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, InvalidValueException}
import com.evernym.verity.util2.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.util2.RouteId
import com.evernym.verity.util2.Status._
import com.evernym.verity.actor._
import com.evernym.verity.actor.agent.EntityTypeMapper
import com.evernym.verity.actor.agent.msghandler.incoming.{ProcessPackedMsg, ProcessRestMsg}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.cache.ROUTING_DETAIL_CACHE_FETCHER
import com.evernym.verity.cache.base.{Cache, FetcherParam, GetCachedObjectParam, KeyDetail}
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

  def execute: PartialFunction[Any, Future[Any]] = {
    case cmd: Any => withAskTimeoutLogged(executeCmd(cmd, None))
  }

  def forward: PartialFunction[(Any, ActorRef), Unit] = {
    case (cmd, sender) =>
      val futResp = withAskTimeoutLogged(executeCmd(cmd, Option(sender)))
      futResp.recover {
        case e: Exception => ExceptionHandler.handleException(e, sender)
      }
  }

  private def executeCmd: PartialFunction[(Any, Option[ActorRef]), Future[AskResp]] = {
    case (cmd, senderOpt) =>
      cmd match {
        case sr: SetRoute               => setRoute(sr)
        case gr: GetRoute               => getRouteInfo(gr.routeDID)
        // flow diagram: fwd, step 5 -- trigger routing for packed messages.
        case efw: PackedMsgRouteParam   => routePackedMsg(efw)(senderOpt)
        // flow diagram: ctl + proto, step 11; SIG, step 6 -- trigger routing for internal message.
        case im: InternalMsgRouteParam  => routeInternalMsg(im)(senderOpt)
        // flow diagram: rest, step 6 -- trigger routing for REST message.
        case rm: RestMsgRouteParam      => routeRestMsg(rm)(senderOpt)
      }
  }

  implicit lazy val timeout: Timeout = buildTimeout(appConfig, TIMEOUT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS,
    DEFAULT_GENERAL_ACTOR_ASK_TIMEOUT_IN_SECONDS)

  val logger: Logger = getLoggerByClass(classOf[AgentMsgRouter])

  lazy val fetchers: Map[FetcherParam, CacheValueFetcher] = Map (
    ROUTING_DETAIL_CACHE_FETCHER -> new RoutingDetailCacheFetcher(system, appConfig)
  )
  lazy val routingCache: Cache = new Cache("RC", fetchers)

  lazy val agencyAgentRegion: ActorRef = ClusterSharding(system).shardRegion(AGENCY_AGENT_REGION_ACTOR_NAME)
  lazy val agencyAgentPairwiseRegion: ActorRef = ClusterSharding(system).shardRegion(AGENCY_AGENT_PAIRWISE_REGION_ACTOR_NAME)
  lazy val routeRegion: ActorRef = ClusterSharding(system).shardRegion(ROUTE_REGION_ACTOR_NAME)

  private def setRoute(sr: SetRoute): Future[AskResp] = {
    val fut = routeRegion ? ForIdentifier(sr.routeDID, StoreRoute(sr.actorAddressDetail))
    Future(AskResp(fut, Option(s"setting route: $sr")))
  }

  private def getRouteInfo(route: RouteId): Future[AskResp] = {
    val routeDID = AgentMsgRouter.getDIDForRoute(route) match {
      case Success(did) => did
      case Failure(e) =>
        logger.error(s"Could not extract DID for route: $route")
        throw e
    }
    val startTime = LocalDateTime.now
    logger.debug("get route info started", (LOG_KEY_SRC_DID, routeDID))
    val futResp =
      getRouteInfoViaCache(routeDID)
        .map { r =>
          val curTime = LocalDateTime.now
          val millis = ChronoUnit.MILLIS.between(startTime, curTime)
          logger.debug(s"get route info finished, time taken (in millis): $millis", (LOG_KEY_SRC_DID, routeDID))
          r
        }
    Future(AskResp(futResp, Option(s"getting route info: $routeDID")))
  }

  private def getRouteInfoViaCache(routeId: RouteId): Future[Option[ActorAddressDetail]] = {
    val gcop = GetCachedObjectParam(KeyDetail(routeId, required = false), ROUTING_DETAIL_CACHE_FETCHER)
    routingCache.getByParamAsync(gcop).map { cqr =>
      cqr.getActorAddressDetailOpt(routeId)
    }
  }

  def getRouteRegionActor(route: RouteId): Future[RouteInfo] = {
    getRouteInfo(route)
      .flatMap(_.actualFut)
      .map {
        case Some(ad: ActorAddressDetail) =>
          val regionActor: ActorRef = getActorTypeToRegions(ad.actorTypeId)
          val ri = RouteInfo(regionActor, ad.address)
          logger.debug("routing info for route '" + route + "' is: " + ri, (LOG_KEY_SRC_DID, route))
          ri
        case None =>
          throw new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode, msg = Option(s"agent not created for route: $route"))
      }
  }

  private def sendCmdToGivenActor(to: ActorRef, cmd: ForIdentifier)
                                 (implicit senderOpt: Option[ActorRef]): AskResp = {
    val fut = senderOpt match {
      case Some(sndr) => (to ? cmd).map { r => sndr ! r }
      case None => to ? cmd
    }
    AskResp(fut, Option(s"region actor: $to, route: ${cmd.id}, cmd class: ${cmd.msg.getClass.getSimpleName}"))
  }

  private def routePackedMsg(pmrp: PackedMsgRouteParam)(implicit senderOpt: Option[ActorRef]): Future[AskResp] = {
    // flow diagram: fwd + ctl + proto + legacy, step 5 -- Find route to relevant actor, send inner msg.
    // As far as I can tell, what the next line does is look up the actor for a given toRoute
    // value. That value could be a verkey or an unqualified DID. I'm not sure why the concept
    // of sharding region enters into it; shouldn't this just be an actor ID that has the region
    // baked into it in some way? When I follow the function and its internals, I see what looks
    // like a mapping between actor type and regions, which doesn't seem to care about shards in
    // a cluster. ?
    getRouteRegionActor(pmrp.toRoute)
      .map { ri =>
        logDuration(logger, "sending msg to target actor") {
          logger.debug("sending msg to target actor")
          sendCmdToGivenActor(ri.actorRef, ForIdentifier(ri.entityId, ProcessPackedMsg(pmrp.packedMsg, pmrp.reqMsgContext)))
        }
    }
  }

  private def routeInternalMsg(imrp: InternalMsgRouteParam)(implicit senderOpt: Option[ActorRef]): Future[AskResp] = {
    // flow diagram: ctl + proto, step 12; sig, step 7
    getRouteRegionActor(imrp.toRoute)
      .map { ri =>
        sendCmdToGivenActor(ri.actorRef, ForIdentifier(ri.entityId, imrp.msg))
      }
  }

  private def routeRestMsg(rmrp: RestMsgRouteParam)(implicit senderOpt: Option[ActorRef]): Future[AskResp] = {
    // flow diagram: rest, step 7
    getRouteRegionActor(rmrp.toRoute)
      .map { ri =>
        logDuration(logger, "sending rest msg to target actor") {
          sendCmdToGivenActor(ri.actorRef, ForIdentifier(ri.entityId, ProcessRestMsg(rmrp.msg, rmrp.restMsgContext)))
        }
      }
  }

  private def withAskTimeoutLogged(futWrapper: Future[AskResp]): Future[Any] = {
    futWrapper.flatMap { ar =>
      ar.actualFut.recover {
        case ate: AskTimeoutException =>
          logger.error(s"ask timed out => ${ar.reason.getOrElse("no details available")}")
          throw ate
        case x => throw x
      }
    }
  }

  protected def getActorTypeToRegions(actorTypeId: Int): ActorRef = actorTypeToRegions(actorTypeId)

  private lazy val actorTypeToRegions = EntityTypeMapper.buildRegionMappings(appConfig, system)
}

object AgentMsgRouter {
  def getDIDForRoute(route: RouteId): Try[DID] = {
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

    // For 32-bit string it's Base58-encoded value will have ~45 characters
    // If route string has significantly longer value, we can assume it's not valid
    if (route.length > 100) { // value that definitely cover 32 byte payload
      return Failure(new InvalidValueException(Some("Route value is too long")))
    }
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

  type AskTimeoutErrorMsg = String
}

case class InternalMsgRouteParam(toRoute: RouteId, msg: Any)
case class PackedMsgRouteParam(toRoute: RouteId, packedMsg: PackedMsg, reqMsgContext: ReqMsgContext)
case class RestMsgRouteParam(toRoute: RouteId, msg: String, restMsgContext: RestMsgContext)

case class AskResp(actualFut: Future[Any], reason: Option[String]=None)

case class SetRoute(routeDID: DID, actorAddressDetail: ActorAddressDetail) extends ActorMessage
case class GetRoute(routeDID: DID) extends ActorMessage
