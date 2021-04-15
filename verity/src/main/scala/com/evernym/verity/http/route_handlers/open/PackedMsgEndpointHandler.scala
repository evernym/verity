package com.evernym.verity.http.route_handlers.open

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, MediaTypes, RemoteAddress}
import akka.http.scaladsl.server.Directives.{as, complete, entity, extractClientIP, extractRequest, handleExceptions, logRequestResult, path, post, reject, _}
import akka.http.scaladsl.server.Route
import com.evernym.verity.Exceptions.BadRequestErrorException
import com.evernym.verity.Status
import com.evernym.verity.actor.agent.DidPair
import com.evernym.verity.constants.Constants.CLIENT_IP_ADDRESS
import com.evernym.verity.actor.agent.agency.AgencyPackedMsgHandler
import com.evernym.verity.actor.agent.msgrouter.InternalMsgRouteParam
import com.evernym.verity.actor.base.Done
import com.evernym.verity.http.common.HttpCustomTypes
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.http.common.CustomExceptionHandler._
import com.evernym.verity.util.{PackedMsgWrapper, ReqMsgContext}
import com.evernym.verity.actor.wallet.PackedMsg
import com.evernym.verity.agentmsg.msgpacker.UnpackParam
import com.evernym.verity.vault.{KeyParam, WalletAPIParam}

import scala.concurrent.Future


trait PackedMsgEndpointHandler
  extends AgencyPackedMsgHandler { this: HttpRouteWithPlatform =>

  def getAgencyDidPairFut: Future[DidPair]
  implicit def wap: WalletAPIParam

  protected def handleAgentMsgResponse: PartialFunction[(Any, ReqMsgContext), ToResponseMarshallable] = {

    case (pm: PackedMsg, rmc) =>
      incrementAgentMsgSucceedCount
      logger.info(s"[${rmc.id}] [outgoing response] [$OK] packed message")
      HttpEntity(MediaTypes.`application/octet-stream`, pm.msg)

    case (Done, rmc) =>
      logger.info(s"[${rmc.id}] [outgoing response] [$OK]")
      OK

    case (e, rmc) =>
      incrementAgentMsgFailedCount(Map("class" -> "ProcessFailure"))
      val errResp = handleUnexpectedResponse(e)
      logger.info(s"[${rmc.id}] [outgoing response] [${errResp.status}]")
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
      logger.error(s"DATA length: ${data.length}")
      // Fixme: this size is quick solution to ensure that received data won't exceed 400k limit of Dynamodb messages.
      //       Number below is selected during testing and not intended to be 100% accurate
      if (data.length > 400000) {
        throw new BadRequestErrorException(Status.VALIDATION_FAILED.statusCode, Option("Payload size is too big"))
      }
      complete {
        processPackedMsg(PackedMsgWrapper(data, reqMsgContext)).map[ToResponseMarshallable] {
          handleAgentMsgResponse(_, reqMsgContext)
        }
      }
    }
  }

  protected def handleAgentMsgReqForUnsupportedContentType()(implicit reqMsgContext: ReqMsgContext): Route = {
    incrementAgentMsgFailedCount(Map("class" -> "InvalidContentType"))
    reject
  }

  protected def handleAgentMsgReq(implicit req: HttpRequest, remoteAddress: RemoteAddress): Route = {
    // flow diagram: fwd + ctl + proto + legacy, step 1 -- Packed msg arrives.
    incrementAgentMsgCount
    implicit val reqMsgContext: ReqMsgContext = ReqMsgContext(initData = Map(CLIENT_IP_ADDRESS -> clientIpAddress))
    logger.info(s"[${reqMsgContext.id}] [incoming request] [POST] packed message ${reqMsgContext.clientIpAddressLogStr}")
    MsgRespTimeTracker.recordReqReceived(reqMsgContext.id)    //tracing metrics related
    req.entity.contentType.mediaType match {
      case MediaTypes.`application/octet-stream` | HttpCustomTypes.MEDIA_TYPE_SSI_AGENT_WIRE =>
        handleAgentMsgReqForOctetStreamContentType
      case x =>
        logger.info(s"[${reqMsgContext.id}] [outgoing response] content type not supported")
        handleAgentMsgReqForUnsupportedContentType()
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
