package com.evernym.verity.http.route_handlers.restricted

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.agent.agency.{CreateKey, SetEndpoint, UpdateEndpoint}
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute}
import com.evernym.verity.actor.{AgencyPublicDid, EndpointSet}
import com.evernym.verity.cache.KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER
import com.evernym.verity.cache.base.{GetCachedObjectParam, KeyDetail}
import com.evernym.verity.constants.Constants.AGENCY_DID_KEY
import com.evernym.verity.did.DidStr
import com.evernym.verity.http.HttpUtil.optionalEntityAs
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor
import com.evernym.verity.http.route_handlers.restricted.models.CreateAgencyKey
import com.evernym.verity.util.Util.getNewActorId
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, ForbiddenErrorException}
import com.evernym.verity.util2.Status.{AGENT_NOT_YET_CREATED, StatusDetail, getUnhandledError}

import scala.concurrent.Future

/**
 * handles http routes used during setting up agency agent
 */
trait AgencySetupEndpointHandler extends BaseRequestHandler {
  this: PlatformWithExecutor =>

  protected def getActorIdByDID(toDID: DidStr): Future[Either[StatusDetail, ActorAddressDetail]] = {
    val respFut = platform.agentActorContext.agentMsgRouter.execute(GetRoute(toDID))
    respFut map {
      case Some(aa: ActorAddressDetail) => Right(aa)
      case None => Left(AGENT_NOT_YET_CREATED)
      case e => Left(getUnhandledError(e))
    }
  }

  protected def getOrCreateActorIdFut(did: DidStr): Future[Either[StatusDetail, String]] = getActorIdByDID(did) map {
    case Right(aa: ActorAddressDetail) => Right(aa.address)
    case Left(AGENT_NOT_YET_CREATED) => Right(getNewActorId)
    case e => Left(getUnhandledError(e))
  }

  protected def getAgencyDIDOptFut: Future[Option[String]] = {
    val gcop = GetCachedObjectParam(KeyDetail(AGENCY_DID_KEY, required = false), KEY_VALUE_MAPPER_ACTOR_CACHE_FETCHER)
    platform.agentActorContext.generalCache.getByParamAsync(gcop).map { cqr =>
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
    case _: EndpointSet => OK
    case ad: AgencyPublicDid => handleExpectedResponse(ad)
    case e => handleUnexpectedResponse(e)
  }

  protected val setupRoutes: Route = {
    handleRestrictedRequest(exceptionHandler) { (_, _) =>
      pathPrefix("agency" / "internal" / "setup") {
        path("key") {
          (post & pathEnd & optionalEntityAs[CreateAgencyKey]) { cakOpt =>
            complete {
              createKey(cakOpt).map {
                adminMsgResponseHandler
              }
            }
          }
        } ~
          path("endpoint") {
            (post & pathEnd) {
              complete {
                setEndpoint(SetEndpoint).map {
                  adminMsgResponseHandler
                }
              }
            } ~
              (put & pathEnd) {
                complete {
                  setEndpoint(UpdateEndpoint).map {
                    adminMsgResponseHandler
                  }
                }
              }
          }
      }
    }
  }
}
