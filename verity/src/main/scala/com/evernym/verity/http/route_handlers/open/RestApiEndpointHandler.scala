package com.evernym.verity.http.route_handlers.open

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CustomHeader
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.BasicDirectives.extract
import akka.http.scaladsl.server.directives.HeaderDirectives.optionalHeaderValueByName
import akka.http.scaladsl.server.{Directive1, Route}
import com.evernym.verity.actor.agent.msghandler.outgoing.JsonMsg
import com.evernym.verity.actor.agent.msgrouter.RestMsgRouteParam
import com.evernym.verity.actor.base.Done
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.ConfigConstants.REST_API_ENABLED
import com.evernym.verity.constants.Constants.{API_KEY_HTTP_HEADER, CLIENT_REQUEST_ID_HTTP_HEADER}
import com.evernym.verity.did.didcomm.v1.Thread
import com.evernym.verity.did.didcomm.v1.messages.{MsgFamily, MsgType}
import com.evernym.verity.http.LoggingRouteUtil.{incomingLogMsg, outgoingLogMsg}
import com.evernym.verity.http.common.models.StatusDetailResp
import com.evernym.verity.http.common.{BaseRequestHandler, BaseResponseHandler, MetricsSupport}
import com.evernym.verity.http.route_handlers.PlatformWithExecutor
import com.evernym.verity.http.route_handlers.open.models.{RestAcceptedResponse, RestErrorResponse, RestOKResponse}
import com.evernym.verity.protocol.engine.ProtoRef
import com.evernym.verity.util.{ReqMsgContext, RestAuthContext, RestMsgContext}
import com.evernym.verity.util2.Exceptions.{BadRequestErrorException, FeatureNotEnabledException, UnauthorisedErrorException}
import com.evernym.verity.util2.{ActorErrorResp, Status}
import org.json.JSONObject

import java.util.UUID
import scala.util.Try


final case class `API-REQUEST-ID`(id: String) extends CustomHeader {
  override def name(): String = CLIENT_REQUEST_ID_HTTP_HEADER

  override def value(): String = id

  override def renderInRequests(): Boolean = true

  override def renderInResponses(): Boolean = true
}

trait RestApiEndpointHandler extends BaseRequestHandler with MetricsSupport {
  this: PlatformWithExecutor =>

  import RestResponseHandler._

  lazy val restApiEnabled: Boolean = appConfig.getBooleanOption(REST_API_ENABLED).getOrElse(false)

  protected def checkIfRestApiEnabled(): Unit = {
    if (!restApiEnabled) {
      logger.info("received request on disabled REST api")
      throw new FeatureNotEnabledException(Status.NOT_IMPLEMENTED.statusCode, Option(Status.NOT_IMPLEMENTED.statusMsg))
    }
  }

  private def logIncoming(route: String,
                          protoRef: ProtoRef,
                          thid: Option[String],
                          method: HttpMethod,
                          reqMsgContext: ReqMsgContext): Unit = {
    implicit val reqMsgContextIm: ReqMsgContext = reqMsgContext

    logger.whenInfoEnabled {
      val target = s"REST API at $protoRef ${thid.map(s"on thread:" + _).getOrElse("")}"
      val buildLogMsg = incomingLogMsg(
        target,
        method,
        Some(route),
        Some("REST_API")
      )
      logger.underlying.info(buildLogMsg._1, buildLogMsg._2: _*)
    }
  }

  private def logOutgoing(route: String,
                          protoRef: ProtoRef,
                          thid: Option[String],
                          status: StatusCode,
                          reqMsgContext: ReqMsgContext): Unit = {
    logger.whenInfoEnabled {
      implicit val reqMsgContextIm: ReqMsgContext = reqMsgContext

      val target = s"REST API at $protoRef ${thid.map(s"on thread:" + _).getOrElse("")}"
      val buildLogMsg = outgoingLogMsg(
        target,
        status,
        Some(route),
        Some("REST_API")
      )
      logger.underlying.info(buildLogMsg._1, buildLogMsg._2: _*)
    }
  }

  protected def handleRestMsgReq(route: String, protoRef: ProtoRef, auth: RestAuthContext, thid: Option[String], reqMsgContext: ReqMsgContext): Route = {
    incrementAgentMsgCount()

    logIncoming(route, protoRef, thid, HttpMethods.POST, reqMsgContext)

    entity(as[String]) { payload =>
      val msgType = extractMsgType(payload)
      checkMsgFamily(msgType, protoRef)
      val restMsgContext: RestMsgContext = RestMsgContext(
        msgType,
        auth,
        Option(Thread(thid)),
        reqMsgContext
      )

      complete {
        platform.agentActorContext.agentMsgRouter.execute(RestMsgRouteParam(route, payload, restMsgContext))
          .map(responseHandler(route, protoRef, thid, reqMsgContext))
      }
    }
  }

  protected def handleRestGetStatusReq(route: String, protoRef: ProtoRef, auth: RestAuthContext,
                                       thid: Option[String], params: Map[String, String], reqMsgContext: ReqMsgContext): Route = {
    incrementAgentMsgCount()

    logIncoming(route, protoRef, thid, HttpMethods.GET, reqMsgContext)

    val msgType = buildGetStatusMsgType(protoRef, params)
    val msg = buildGetStatusMsg(msgType, params)
    val restMsgContext: RestMsgContext = RestMsgContext(msgType, auth, Option(Thread(thid)), reqMsgContext, sync = true)

    complete {
      platform.agentActorContext.agentMsgRouter.execute(RestMsgRouteParam(route, msg, restMsgContext))
        .map(responseHandler(route, protoRef, thid, reqMsgContext))
    }
  }

  def withClientRequestId(resp: HttpResponse, reqMsgContext: ReqMsgContext): HttpResponse = {
    reqMsgContext.clientReqId.map { id =>
      resp.withHeaders(`API-REQUEST-ID`(id))
    }.getOrElse(resp)
  }

  protected def responseHandler(route: String, protoRef: ProtoRef, thid: Option[String], reqMsgContext: ReqMsgContext): PartialFunction[Any, HttpResponse] = {
    case br: ActorErrorResp =>
      incrementAgentMsgFailedCount(Map("class" -> "ProcessFailure"))
      val resp = handleUnexpectedResponse(br)
      logOutgoing(route, protoRef, thid, resp.status, reqMsgContext)
      withClientRequestId(resp, reqMsgContext)
    case Done =>
      incrementAgentMsgSucceedCount()
      val resp = HttpResponse(
        StatusCodes.Accepted,
        entity = HttpEntity(
          ContentType(MediaTypes.`application/json`),
          DefaultMsgCodec.toJson(RestAcceptedResponse())
        )
      )
      logOutgoing(route, protoRef, thid, resp.status, reqMsgContext)
      withClientRequestId(resp, reqMsgContext)
    case respStr: String =>
      incrementAgentMsgSucceedCount()
      val resp = HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity(
          ContentType(MediaTypes.`application/json`),
          DefaultMsgCodec.toJson(RestOKResponse(respStr))
        )
      )
      logOutgoing(route, protoRef, thid, resp.status, reqMsgContext)
      withClientRequestId(resp, reqMsgContext)
    case jsonMsg: JsonMsg =>
      incrementAgentMsgSucceedCount()
      val resp = HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity(
          ContentType(MediaTypes.`application/json`),
          DefaultMsgCodec.toJson(RestOKResponse(new JSONObject(jsonMsg.msg)))
        )
      )
      logOutgoing(route, protoRef, thid, resp.status, reqMsgContext)
      withClientRequestId(resp, reqMsgContext)
    case native: Any =>
      incrementAgentMsgSucceedCount()
      val resp = HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity(
          ContentType(MediaTypes.`application/json`),
          DefaultMsgCodec.toJson(RestOKResponse(native))
        )
      )
      logOutgoing(route, protoRef, thid, resp.status, reqMsgContext)
      withClientRequestId(resp, reqMsgContext)
  }

  protected def extractMsgType(payload: String): MsgType = {
    try {
      DefaultMsgCodec.msgTypeFromDoc(DefaultMsgCodec.docFromStrUnchecked(payload))
    } catch {
      case e: Exception =>
        val sanitizedPayload = Try {
          val jsonObject = new JSONObject(payload)
          jsonObject.remove("phoneNumber")
          jsonObject.toString
        }.getOrElse(payload)
        logger.warn(s"Invalid payload. Exception: $e, Payload: $sanitizedPayload")
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
    params.foreach { case (key, value) =>
      jsonMsg.put(key, value)
    }
    jsonMsg.toString
  }

  protected def checkMsgFamily(msgType: MsgType, protoRef: ProtoRef): Unit = {
    if (!protoRef.isInFamily(msgType))
      throw new BadRequestErrorException(Status.VALIDATION_FAILED.statusCode, Option("Invalid protocol family and/or version"))
  }

  protected def extractAuthHeader: Directive1[RestAuthContext] = {
    val lowerCaseName = API_KEY_HTTP_HEADER.toLowerCase
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

  protected val restRoutes: Route = {
    handleRequest(exceptionHandler) { (_, remoteAddress) =>
      pathPrefix("api") {
        checkIfRestApiEnabled()
        extractAuthHeader { auth =>
          optionalHeaderValueByName(CLIENT_REQUEST_ID_HTTP_HEADER) { requestId =>
            path(Segment / Segment / Segment / Segment.?) { (route, protocolFamily, version, threadId) =>
              val protoRef = ProtoRef(protocolFamily, version)
              val ip = remoteAddress.getAddress().get.getHostAddress
              implicit val reqMsgContext: ReqMsgContext = ReqMsgContext.empty
                .withClientIpAddress(ip)
                .withClientReqId(requestId)
              MsgRespTimeTracker.recordReqReceived(reqMsgContext.id) //tracing related
              parameterMap { params =>
                post {
                  handleRestMsgReq(route, protoRef, auth, threadId, reqMsgContext)
                } ~
                  get {
                    handleRestGetStatusReq(route, protoRef, auth, threadId, params, reqMsgContext)
                  }
              }
            }
          }
        }
      }
    }
  }
}

object RestResponseHandler extends BaseResponseHandler {
  override def createResponse(sdr: StatusDetailResp): RestErrorResponse = RestErrorResponse(sdr.statusCode, sdr.statusMsg)
}