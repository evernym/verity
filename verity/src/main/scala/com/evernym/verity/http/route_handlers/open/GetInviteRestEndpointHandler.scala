package com.evernym.verity.http.route_handlers.open

import akka.cluster.sharding.ClusterSharding
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import com.evernym.verity.actor.agent.msghandler.outgoing.ProtocolSyncRespMsg
import com.evernym.verity.actor.agent.msgrouter.{ActorAddressDetail, GetRoute}
import com.evernym.verity.actor.resourceusagethrottling.RESOURCE_TYPE_ENDPOINT
import com.evernym.verity.actor.resourceusagethrottling.tracking.ResourceUsageCommon
import com.evernym.verity.actor.{ActorItemDetail, ForIdentifier, GetDetail}
import com.evernym.verity.agentmsg.msgfamily.MsgFamilyUtil
import com.evernym.verity.constants.Constants.{UNKNOWN_RECIP_PARTICIPANT_ID, UNKNOWN_SENDER_PARTICIPANT_ID}
import com.evernym.verity.did.DidStr
import com.evernym.verity.did.didcomm.v1.messages.MsgId
import com.evernym.verity.http.common.AllowedIpsResolver.extractIp
import com.evernym.verity.http.common.BaseRequestHandler
import com.evernym.verity.http.common.CustomResponseHandler._
import com.evernym.verity.http.route_handlers.PlatformWithExecutor
import com.evernym.verity.protocol.container.actor.{ActorProtocol, MsgEnvelope, ProtocolCmd}
import com.evernym.verity.protocol.engine.{DEFAULT_THREAD_ID, ProtoDef}
import com.evernym.verity.protocol.protocols.connecting.common.InviteDetail
import com.evernym.verity.protocol.protocols.connecting.v_0_5.{GetInviteDetail_MFV_0_5, ConnectingProtoDef => ConnectingProtoDef_v_0_5}
import com.evernym.verity.protocol.protocols.connecting.v_0_6.{GetInviteDetail_MFV_0_6, ConnectingProtoDef => ConnectingProtoDef_v_0_6}
import com.evernym.verity.util.Base64Util
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, NotImplementedErrorException}
import com.evernym.verity.util2.Status.{AGENT_NOT_YET_CREATED, DATA_NOT_FOUND, VALIDATION_FAILED}
import org.json.JSONObject

import scala.concurrent.Future

/**
 * rest api routes to get invitation via token
 */

trait GetInviteRestEndpointHandler
  extends BaseRequestHandler {
  this: PlatformWithExecutor with ResourceUsageCommon =>

  protected def getInviteResponseHandler: PartialFunction[Any, ToResponseMarshallable] = {
    case invDetail: InviteDetail => handleExpectedResponse(invDetail)
    case jsonMsg: JSONObject => HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentType(MediaTypes.`application/json`), jsonMsg.toString(2)))
    case e => handleUnexpectedResponse(e)
  }

  protected def getTokenFut(implicit token: String): Future[Any] = {
    platform.tokenToActorItemMapper ? GetDetail
  }

  protected def getInviteDetail(aid: ActorItemDetail): Future[Any] = {
    implicit val actorEntityId: String = aid.actorEntityId
    val protocolDefs: Set[ProtoDef] = Set(ConnectingProtoDef_v_0_5, ConnectingProtoDef_v_0_6)
    val msg = protocolDefs.find(pd => ActorProtocol.buildTypeName(pd) == aid.regionTypeName) match {
      case Some(ConnectingProtoDef_v_0_5) => GetInviteDetail_MFV_0_5(aid.uid)
      case Some(ConnectingProtoDef_v_0_6) => GetInviteDetail_MFV_0_6(aid.uid)
      case _ =>
        throw new NotImplementedErrorException("get invite detail not supported for given token")
    }

    val regionActor = ClusterSharding(platform.agentActorContext.system).shardRegion(aid.regionTypeName)
    //TODO (msg-extractor): come back here and see if values given in MsgEnvelope are correct or not?
    val pem = MsgEnvelope(msg.typedMsg.msg, msg.typedMsg.msgType, UNKNOWN_RECIP_PARTICIPANT_ID, UNKNOWN_SENDER_PARTICIPANT_ID,
      Option(MsgFamilyUtil.getNewMsgUniqueId), Option(DEFAULT_THREAD_ID))

    val cmd = ProtocolCmd(
      pem,
      None
    )

    val getInviteDetailFut = regionActor ? ForIdentifier(actorEntityId, cmd)
    handleGetInviteDetailFut(getInviteDetailFut)
  }

  protected def getInviteDetailByDIDAndUid(DID: DidStr, uid: MsgId): Future[Any] = {
    val gr = GetRoute(DID)
    val respFut = platform.agentActorContext.agentMsgRouter.execute(gr) flatMap {
      case Some(aa: ActorAddressDetail) =>
        implicit val actorEntityId: String = aa.address
        platform.agentPairwise ? GetInviteDetail_MFV_0_5(uid)
      case None => Future.successful(new BadRequestErrorException(AGENT_NOT_YET_CREATED.statusCode,
        Option(AGENT_NOT_YET_CREATED.statusMsg)))
      case e => Future.successful(e)
    }
    handleGetInviteDetailFut(respFut)
  }

  protected def handleGetInviteDetailFut(fut: Future[Any]): Future[Any] = {
    fut map {
      case ProtocolSyncRespMsg(msg: Any, _) => msg //this is when msg is directly sent to connecting region actor
      case id: InviteDetail => id
      case e => e
    }
  }

  protected def getInviteDetailByToken(token: String): Future[Any] = {
    getTokenFut(token) flatMap {
      case Some(td: ActorItemDetail) => getInviteDetail(td)
      case None => Future.successful(new BadRequestErrorException(DATA_NOT_FOUND.statusCode, Option(DATA_NOT_FOUND.statusMsg)))
    }
  }

  protected def decodeAriesInvitation(base64inv: String): JSONObject = {
    try {
      new JSONObject(new String(Base64Util.getBase64UrlDecoded(base64inv)))
    } catch {
      case _: Exception =>
        throw new BadRequestErrorException(VALIDATION_FAILED.statusCode, Option("Invalid payload"))
    }
  }

  protected val getInviteRoute: Route =
    handleRequest(exceptionHandler) { (_, remoteAddress) =>
      pathPrefix("agency") {
        pathPrefix("invite") {
          (get & pathEnd) {
            parameters(Symbol("t")) { token =>
              addUserResourceUsage(RESOURCE_TYPE_ENDPOINT, "GET_agency_invite", extractIp(remoteAddress), None)
              complete {
                getInviteDetailByToken(token).map {
                  getInviteResponseHandler
                }
              }
            }
          } ~
            pathPrefix(Segment) { DID =>
              (get & pathEnd) {
                parameters(Symbol("uid")) { uid =>
                  addUserResourceUsage(RESOURCE_TYPE_ENDPOINT, "GET_agency_invite_did", extractIp(remoteAddress), None)
                  complete {
                    getInviteDetailByDIDAndUid(DID, uid).map {
                      getInviteResponseHandler
                    }
                  }
                }
              }
            }
        } ~
          pathPrefix("msg") {
            (get & pathEnd) {
              parameters("c_i") { inv =>
                addUserResourceUsage(RESOURCE_TYPE_ENDPOINT, "GET_agency_invite_aries", extractIp(remoteAddress), None)
                complete {
                  getInviteResponseHandler(decodeAriesInvitation(inv))
                }
              } ~
                parameters("oob") { inv =>
                  addUserResourceUsage(RESOURCE_TYPE_ENDPOINT, "GET_agency_invite_aries", extractIp(remoteAddress), None)
                  complete {
                    getInviteResponseHandler(decodeAriesInvitation(inv))
                  }
                }
            }
          }
      }
    }
}
