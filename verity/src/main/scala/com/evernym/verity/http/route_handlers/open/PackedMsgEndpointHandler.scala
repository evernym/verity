package com.evernym.verity.http.route_handlers.open

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{HttpEntity, HttpMethod, HttpMethods, HttpRequest, MediaTypes, RemoteAddress, StatusCode}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractClientIP, extractRequest, handleExceptions, logRequestResult, path, post, reject, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.actor.agent.agency.AgencyPackedMsgHandler
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.base.Done
import com.evernym.verity.http.common.HttpCustomTypes
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.util.{PackedMsgWrapper, ReqMsgContext}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.agentmsg.msgpacker.UnpackParam
import com.evernym.verity.http.LoggingRouteUtil.{incomingLogMsg, outgoingLogMsg}
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}

import scala.concurrent.Future


trait PackedMsgEndpointHandler
  extends AgencyPackedMsgHandler { this: HttpRouteWithPlatform =>

  def getAgencyDidPairFut: Future[DidPair]
  implicit def wap: WalletAPIParam

  private def logOutgoing(status: StatusCode)
                         (implicit reqMsgContext: ReqMsgContext): Unit = {
    logger.whenInfoEnabled {
      val target = s"did comm message handler"
      val buildLogMsg = outgoingLogMsg(
        target,
        status,
        None,
        None
      )
      logger.info(buildLogMsg._1, buildLogMsg._2: _*)
    }
  }

  protected def handleAgentMsgResponse: PartialFunction[(Any, ReqMsgContext), ToResponseMarshallable] = {

    case (pm: PackedMsg, rmc) =>
      incrementAgentMsgSucceedCount
      logOutgoing(OK)(rmc)
      HttpEntity(MediaTypes.`application/octet-stream`, pm.msg)

    case (Done, rmc) =>
      logOutgoing(OK)(rmc)
      OK

    case (e, rmc) =>
      incrementAgentMsgFailedCount(Map("class" -> "ProcessFailure"))
      val errResp = handleUnexpectedResponse(e)
      logOutgoing(errResp.status)(rmc)
      errResp
  }

  /**
   * PREFERABLE: sends the packed message to the agency agent
   * when used this approach, earlier we found performance
   * @param pmw packed msg wrapper
   * @return
   */
  protected def sendPackedMsgToAgencyAgent(pmw: PackedMsgWrapper): Future[Any] = {
    getAgencyDidPairFut flatMap { adp =>
      agentActorContext.agentMsgRouter.execute(InternalMsgRouteParam(adp.DID, pmw))
    }
  }

  /**
   * NOT-PREFERABLE: processes the packed message locally
   * @param pmw packed msg wrapper
   * @return
   */
  protected def processPackedMsg(pmw: PackedMsgWrapper): Future[Any] = {
    // flow diagram: fwd + ctl + proto + legacy, step 3 -- Decrypt and check message type.
    getAgencyDidPairFut flatMap { adp =>
      agentActorContext.agentMsgTransformer.unpackAsync(
        pmw.msg, KeyParam(Left(adp.verKey)), UnpackParam(isAnonCryptedMsg = true)
      ).flatMap { implicit amw =>
        handleUnpackedMsg(pmw)
      }
    }
  }

  protected def handleAgentMsgReqForOctetStreamContentType(implicit reqMsgContext: ReqMsgContext): Route = {
    // flow diagram: fwd + ctl + proto + legacy, step 2 -- Detect binary content.
    import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
    entity(as[Array[Byte]]) { data =>
      complete {
        processPackedMsg(PackedMsgWrapper(data, reqMsgContext)).map[ToResponseMarshallable] {
          handleAgentMsgResponse(_, reqMsgContext)
        }
      }
    }
  }

  private def logIncoming(method: HttpMethod)
                         (implicit reqMsgContext: ReqMsgContext): Unit = {
    logger.whenInfoEnabled {
      val target = s"did comm message handler"
      val buildLogMsg = incomingLogMsg(
        target,
        method,
        None,
        None
      )
      logger.info(buildLogMsg._1, buildLogMsg._2: _*)
    }
  }

  protected def handleAgentMsgReq(implicit req: HttpRequest, remoteAddress: RemoteAddress): Route = {
    // flow diagram: fwd + ctl + proto + legacy, step 1 -- Packed msg arrives.
    incrementAgentMsgCount
    implicit val reqMsgContext: ReqMsgContext = ReqMsgContext.empty.withClientIpAddress(clientIpAddress)
    logIncoming(HttpMethods.POST)
    MsgRespTimeTracker.recordReqReceived(reqMsgContext.id)    //tracing metrics related
    req.entity.contentType.mediaType match {
      case MediaTypes.`application/octet-stream` | HttpCustomTypes.MEDIA_TYPE_SSI_AGENT_WIRE | HttpCustomTypes.MEDIA_TYPE_DIDCOMM_ENVELOPE_ENC =>
        handleAgentMsgReqForOctetStreamContentType
      case _ =>
        logger.info(s"[${reqMsgContext.id}] [outgoing response] content type not supported")
        incrementAgentMsgFailedCount(Map("class" -> "InvalidContentType"))
        reject
    }
  }

  protected val packedMsgRoute: Route =
    handleExceptions(exceptionHandler) {
      logRequestResult("agency-service") {
        path("agency" / "msg") {
          extractRequest { implicit req: HttpRequest =>
            extractClientIP { implicit remoteAddress =>
              post {
                handleAgentMsgReq
              }
            }
          }
        }
      }
    }
}
