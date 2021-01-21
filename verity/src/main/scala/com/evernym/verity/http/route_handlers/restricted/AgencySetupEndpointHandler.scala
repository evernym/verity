package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, extractRequest, handleExceptions, logRequestResult, path, pathPrefix, post, put, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.constants.Constants.{AGENCY_DID_KEY, KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID}
import com.evernym.verity.Exceptions.{BadRequestErrorException, ForbiddenErrorException}
import com.evernym.verity.Status.{AGENT_NOT_YET_CREATED, StatusDetail, getUnhandledError}
import com.evernym.verity.actor.agent.agency.{CreateKey, SetEndpoint, UpdateEndpoint}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute}
import com.evernym.verity.actor.{AgencyPublicDid, EndpointSet}
import com.evernym.verity.cache.{CacheQueryResponse, GetCachedObjectParam, KeyDetail}
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.protocol.engine.DID
import com.evernym.verity.util.CreateAgencyKey
import com.evernym.verity.util.Util.getNewActorId

import scala.concurrent.Future
import scala.util.Left

/**
 * handles http routes used during setting up agency agent
 */
trait AgencySetupEndpointHandler { this: HttpRouteWithPlatform =>

  protected def getActorIdByDID(toDID: DID): Future[Either[StatusDetail, ActorAddressDetail]] = {
    val respFut = platform.agentActorContext.agentMsgRouter.execute(GetRoute(toDID))
    respFut map {
      case Some(aa: ActorAddressDetail) => Right(aa)
      case None => Left(AGENT_NOT_YET_CREATED)
      case e => Left(getUnhandledError(e))
    }
  }

  protected def getOrCreateActorIdFut(did: DID): Future[Either[StatusDetail, String]] = getActorIdByDID(did) map {
    case Right(aa: ActorAddressDetail) => Right(aa.address)
    case Left(AGENT_NOT_YET_CREATED) => Right(getNewActorId)
    case e => Left(getUnhandledError(e))
  }

  protected def getAgencyDIDOptFut: Future[Option[String]] = {
    val gcop = GetCachedObjectParam(Set(KeyDetail(AGENCY_DID_KEY, required = false)), KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER_ID)
    platform.agentActorContext.generalCache.getByParamAsync(gcop).mapTo[CacheQueryResponse].map { cqr =>
      cqr.getAgencyDIDOpt
    }
  }

  protected def createKey(cak: Option[CreateAgencyKey]): Future[Any] = {
    getAgencyDIDOptFut flatMap { adOpt =>
      adOpt.map { _ =>
        Future.successful(new ForbiddenErrorException())
      }.getOrElse {
        implicit val id: String = getNewActorId
        platform.agencyAgent ? CreateKey(seed = cak.flatMap(_.seed))
      }
    }
  }

  protected def setEndpoint(cmd: Any): Future[Any] = {
    getAgencyDIDOptFut flatMap { adOpt =>
      adOpt.map { ad =>
        getActorIdByDID(ad) flatMap {
          case Right(addr: ActorAddressDetail) =>
            implicit val id: String = addr.address
            platform.agencyAgent ? cmd
          case Left(e: Any) =>
            Future.successful(getUnhandledError(e))
        }
      }.getOrElse {
        Future.successful(new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode, Option("agency agent not yet created")))
      }
    }
  }

  protected def adminMsgResponseHandler: PartialFunction[Any, ToResponseMarshallable] = {
    case _:EndpointSet        => OK
    case ad: AgencyPublicDid  => handleExpectedResponse(ad)
    case e                    => handleUnexpectedResponse(e)
  }

  protected val setupRoutes: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("agency" / "internal" / "setup") {
          extractRequest { implicit req =>
            extractClientIP { implicit remoteAddress =>
              checkIfInternalApiCalledFromAllowedIPAddresses(clientIpAddress)
              path("key") {
                (post & pathEnd & optionalEntityAs[CreateAgencyKey]) { cakOpt =>
                  complete {
                    createKey(cakOpt).map[ToResponseMarshallable] {
                      adminMsgResponseHandler
                    }
                  }
                }
              } ~
                path("endpoint") {
                  (post & pathEnd) {
                    complete {
                      setEndpoint(SetEndpoint).map[ToResponseMarshallable] {
                        adminMsgResponseHandler
                      }
                    }
                  } ~
                    (put & pathEnd) {
                      complete {
                        setEndpoint(UpdateEndpoint).map[ToResponseMarshallable] {
                          adminMsgResponseHandler
                        }
                      }
                    }
                }
            }
          }
        }
      }
    }
}
