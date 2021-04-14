package com.evernym.verity.http.route_handlers.open

import java.util.UUID
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{complete, entity, handleExceptions, logRequestResult, pathPrefix, _}
import akka.http.scaladsl.server.directives.BasicDirectives.extract
import akka.http.scaladsl.server.{Directive1, Route}
import com.evernym.verity.constants.Constants.CLIENT_IP_ADDRESS
import com.evernym.verity.Exceptions.{BadRequestErrorException, FeatureNotEnabledException, UnauthorisedErrorException}
import com.evernym.verity.actor.agent.msghandler.outgoing.JsonMsg
import com.evernym.verity.actor.agent.msgrouter.RestMsgRouteParam
import com.evernym.verity.actor.base.Done
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.actor.agent.Thread
import com.evernym.verity.config.CommonConfig.REST_API_ENABLED
import com.evernym.verity.http.common.CustomExceptionHandler.handleUnexpectedResponse
import com.evernym.verity.http.common.{ActorResponseHandler, StatusDetailResp}
import com.evernym.verity.http.route_handlers.HttpRouteWithPlatform
import com.evernym.verity.protocol.engine.{MsgFamily, MsgType, ProtoRef}
import com.evernym.verity.util.{ReqMsgContext, RestAuthContext, RestMsgContext}
import com.evernym.verity.{ActorErrorResp, Status}
import org.json.JSONObject


trait RestApiEndpointHandler { this: HttpRouteWithPlatform =>

  lazy val restApiEnabled: Boolean = appConfig.getConfigBooleanOption(REST_API_ENABLED).getOrElse(false)

  protected def checkIfRestApiEnabled(): Unit = {
    if (!restApiEnabled) {
      logger.warn("received request on disabled REST api")
      throw new FeatureNotEnabledException(Status.NOT_IMPLEMENTED.statusCode, Option(Status.NOT_IMPLEMENTED.statusMsg))
    }
  }

  protected def handleRestMsgReq(route: String, protoRef: ProtoRef, auth: RestAuthContext, thid: Option[String])
                      (implicit reqMsgContext: ReqMsgContext): Route = {
    incrementAgentMsgCount
    logger.info(s"[${reqMsgContext.id}] [incoming request] [POST] rest message ${reqMsgContext.clientIpAddressLogStr}")
    entity(as[String]) { payload =>
      if (payload.length > 400000) { //todo decide value
        throw new BadRequestErrorException("GNR-100", Some("Message size is too big")) //todo return proper data
      }
      val msgType = extractMsgType(payload)
      checkMsgFamily(msgType, protoRef)
      val restMsgContext: RestMsgContext = RestMsgContext(msgType, auth, Option(Thread(thid)), reqMsgContext)

      complete {
        platform.agentActorContext.agentMsgRouter.execute(RestMsgRouteParam(route, payload, restMsgContext)) map responseHandler
      }
    }
  }

  protected def handleRestGetStatusReq(route: String, protoRef: ProtoRef, auth: RestAuthContext, thid: Option[String], params: Map[String, String])
                            (implicit reqMsgContext: ReqMsgContext): Route = {
    incrementAgentMsgCount
    logger.info(s"[${reqMsgContext.id}] [incoming request] [GET] rest message ${reqMsgContext.clientIpAddressLogStr}")
    val msgType = buildGetStatusMsgType(protoRef, params)
    val msg = buildGetStatusMsg(msgType, params)
    val restMsgContext: RestMsgContext = RestMsgContext(msgType, auth, Option(Thread(thid)), reqMsgContext, sync = true)

    complete {
      platform.agentActorContext.agentMsgRouter.execute(RestMsgRouteParam(route, msg, restMsgContext)) map responseHandler
    }
  }

  protected def responseHandler(implicit reqMsgContext: ReqMsgContext): PartialFunction[Any, HttpResponse] = {
    case br: ActorErrorResp  =>
      incrementAgentMsgFailedCount(Map("class" -> "ProcessFailure"))
      val errResp = RestExceptionHandler.handleUnexpectedResponse(br)
      logger.info(s"[${reqMsgContext.id}] [outgoing response] [${errResp.status}]")
      errResp
    case Done             =>
      incrementAgentMsgSucceedCount
      logger.info(s"[${reqMsgContext.id}] [outgoing response] [${StatusCodes.Accepted}]")
      HttpResponse(StatusCodes.Accepted, entity=HttpEntity(ContentType(MediaTypes.`application/json`), DefaultMsgCodec.toJson(RestAcceptedResponse())))
    case resp: String     =>
      incrementAgentMsgSucceedCount
      logger.info(s"[${reqMsgContext.id}] [outgoing response] [${StatusCodes.OK}] (string to json response)")
      HttpResponse(StatusCodes.OK, entity=HttpEntity(ContentType(MediaTypes.`application/json`), DefaultMsgCodec.toJson(RestOKResponse(resp))))
    case jsonMsg: JsonMsg =>
      incrementAgentMsgSucceedCount
      logger.info(s"[${reqMsgContext.id}] [outgoing response] [${StatusCodes.OK}] (json response)")
      HttpResponse(StatusCodes.OK, entity=HttpEntity(ContentType(MediaTypes.`application/json`), DefaultMsgCodec.toJson(RestOKResponse(new JSONObject(jsonMsg.msg)))))
    case native: Any      =>
      incrementAgentMsgSucceedCount
      logger.info(s"[${reqMsgContext.id}] [outgoing response] [${StatusCodes.OK}] (native to json response)")
      HttpResponse(StatusCodes.OK, entity=HttpEntity(ContentType(MediaTypes.`application/json`), DefaultMsgCodec.toJson(RestOKResponse(native))))
  }

  protected def extractMsgType(payload: String): MsgType = {
    try {
      DefaultMsgCodec.msgTypeFromDoc(DefaultMsgCodec.docFromStrUnchecked(payload))
    } catch {
      case e: Exception =>
        logger.warn(s"Invalid payload. Exception: $e, Payload: $payload")
        throw new BadRequestErrorException(Status.VALIDATION_FAILED.statusCode, Option("Invalid payload"))
    }
  }

  protected def buildGetStatusMsgType(protoRef: ProtoRef, params: Map[String, String]): MsgType = {
    MsgType(
      MsgFamily.msgQualifierFromQualifierStr(params.getOrElse("family", "123456789abcdefghi1234")),
      protoRef.msgFamilyName,
      protoRef.msgFamilyVersion,
      params.getOrElse("msgName", "get-status")
    )
  }

  protected def buildGetStatusMsg(msgType: MsgType, params: Map[String, String]): String = {
    val jsonMsg = new JSONObject
    msgType.normalizedMsgType
    jsonMsg.put("@type", MsgFamily.typeStrFromMsgType(msgType))
    jsonMsg.put("@id", UUID.randomUUID.toString)
    params.foreach{case (key, value) =>
      jsonMsg.put(key, value)
    }
    jsonMsg.toString
  }

  protected def checkMsgFamily(msgType: MsgType, protoRef: ProtoRef): Unit = {
    if (msgType.protoRef != protoRef)
      throw new BadRequestErrorException(Status.VALIDATION_FAILED.statusCode, Option("Invalid protocol family and/or version"))
  }

  protected def extractAuthHeader: Directive1[RestAuthContext] = {
    val lowerCaseName = "X-API-key".toLowerCase
    extract(_.request.headers.collectFirst {
      case HttpHeader(`lowerCaseName`, value) => value
    } match {
      case Some(value) =>
        value.split(":") match {
          case Array(verKey, signature) => RestAuthContext(verKey, signature)
          case _ => throw new UnauthorisedErrorException
        }
      case None => throw new UnauthorisedErrorException
    })
  }

  protected val restRoutes: Route =
    handleExceptions(RestExceptionHandler.exceptionHandler) {
      logRequestResult("agency-service") {
        pathPrefix("api") {
          extractRequest { implicit req =>
            extractClientIP { implicit remoteAddress =>
              checkIfRestApiEnabled()
              extractAuthHeader { auth =>
                path(Segment/Segment/Segment/Segment.?) { (route, protocolFamily, version, threadId) =>
                  implicit val reqMsgContext: ReqMsgContext = ReqMsgContext(initData = Map(CLIENT_IP_ADDRESS -> clientIpAddress))
                  MsgRespTimeTracker.recordReqReceived(reqMsgContext.id)       //tracing related
                  parameterMap{ params =>
                    post {
                      handleRestMsgReq(route, ProtoRef(protocolFamily, version), auth, threadId)
                    } ~
                    get {
                      handleRestGetStatusReq(route, ProtoRef(protocolFamily, version), auth, threadId, params)
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

sealed trait RestResponse {
  def status: String
}

case class RestErrorResponse(errorCode: String, errorDetails: String, override val status: String="Error")  extends RestResponse
case class RestAcceptedResponse(override val status: String="Accepted") extends RestResponse
case class RestOKResponse(result: Any, override val status: String="OK") extends RestResponse

object RestExceptionHandler extends ActorResponseHandler {
  def createResponse(sdr: StatusDetailResp): Any = RestErrorResponse(sdr.statusCode, sdr.statusMsg)
}