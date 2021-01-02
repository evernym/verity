package com.evernym.verity.http.common

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpEntity, HttpMethod, HttpMethods, HttpRequest, HttpResponse, MediaTypes, ResponseEntity, StatusCodes}
import akka.http.scaladsl.model.StatusCodes.{Accepted, BadRequest, GatewayTimeout, OK}
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.evernym.verity.constants.LogKeyConstants.{LOG_KEY_ERR_MSG, LOG_KEY_REMOTE_ENDPOINT, LOG_KEY_RESPONSE_CODE}
import com.evernym.verity.Exceptions.HandledErrorException
import com.evernym.verity.ExecutionContextProvider.futureExecutionContext
import com.evernym.verity.Status.{BAD_REQUEST, UNHANDLED}
import com.evernym.verity.actor.agent.SpanUtil._
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.util.Util.buildHandledError
import com.evernym.verity.UrlParam
import com.evernym.verity.actor.wallet.PackedMsg
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.util.Left


trait HttpRemoteMsgSendingSvc extends RemoteMsgSendingSvc {

  def logger: Logger = getLoggerByClass(classOf[HttpRemoteMsgSendingSvc])
  implicit def _system: ActorSystem

  def getConnection(up: UrlParam): Flow[HttpRequest, HttpResponse, Any] = {
    if (up.isHttps)
      Http().outgoingConnectionHttps(up.host, up.port)
    else
      Http().outgoingConnection(up.host, up.port)
  }

  def apiRequest(request: HttpRequest)(implicit up: UrlParam): Future[HttpResponse] =
    Source.single(request).via(getConnection(up)).runWith(Sink.head).recover {
      case _ =>
        val errMsg = s"connection not established with remote server: ${up.toString}"
        logger.error(errMsg, (LOG_KEY_REMOTE_ENDPOINT, up.toString))
        HttpResponse(StatusCodes.custom(GatewayTimeout.intValue, errMsg, errMsg))
    }

  def performResponseParsing[T](implicit up: UrlParam, um: Unmarshaller[ResponseEntity, T]):
  PartialFunction[HttpResponse, Future[Either[HandledErrorException, T]]] = {
    case hr: HttpResponse if List(OK, Accepted).contains(hr.status) =>
      logger.debug(s"successful response ('${hr.status.value}') received from '${up.toString}'", (LOG_KEY_REMOTE_ENDPOINT, up.toString))
      Unmarshal(hr.entity).to[T].map(Right(_))

    case hr: HttpResponse if hr.status ==  BadRequest =>
      val error = s"error response ('${hr.status.value}') received from '${up.toString}': (${hr.entity})"
      logger.error(error, (LOG_KEY_REMOTE_ENDPOINT, up.toString), (LOG_KEY_RESPONSE_CODE, BadRequest.intValue), (LOG_KEY_ERR_MSG, BadRequest.reason))
      Unmarshal(hr.entity).to[String].map { _ =>
        Left(buildHandledError(BAD_REQUEST.withMessage(error))) }

    case hr: HttpResponse =>
      val error = s"error response ('${hr.status.value}') received from '${up.toString}': (${hr.entity})"
      logger.error(error, (LOG_KEY_REMOTE_ENDPOINT, up.toString), (LOG_KEY_ERR_MSG, hr.status))
      Unmarshal(hr.entity).to[String].map { _ =>
        Left(buildHandledError(UNHANDLED.withMessage(error)))
      }
  }

  def sendPlainTextMsgToRemoteEndpoint(payload: String, method: HttpMethod = HttpMethods.POST)
                                      (implicit up: UrlParam): Future[Either[HandledErrorException, String]] = {
    logger.info(s"Sending $method to uri ${up.host}:${up.port}/${up.path}")
    val req = HttpRequest(
      method = method,
      uri = s"/${up.path}",
      entity = HttpEntity(payload)
    )
    apiRequest(req).flatMap { response =>
      val prp = performResponseParsing[String]
      prp(response)
    }
  }

  def sendJsonMsgToRemoteEndpoint(payload: String)(implicit up: UrlParam): Future[Either[HandledErrorException, String]] = {
    runWithClientSpan("sendJsonMsgToRemoteEndpoint", "HttpRemoteMsgSendingSvc") {
      logger.info(s"Sending ${HttpMethods.POST} to uri ${up.host}:${up.port}/${up.path}")
      val req = HttpRequest(
        method = HttpMethods.POST,
        uri = s"/${up.path}",
        entity = HttpEntity(MediaTypes.`application/json`, payload)
      )
      apiRequest(req).flatMap { response =>
        val prp = performResponseParsing[String]
        prp(response)
      }
    }
  }

  def sendBinaryMsgToRemoteEndpoint(payload: Array[Byte])(implicit up: UrlParam): Future[Either[HandledErrorException, PackedMsg]] = {
    runWithClientSpan("sendBinaryMsgToRemoteEndpoint", "HttpRemoteMsgSendingSvc") {
      logger.info(s"Sending ${HttpMethods.POST} to uri ${up.host}:${up.port}/${up.path}")
      val req = HttpRequest(
        method = HttpMethods.POST,
        uri = s"/${up.path}",
        entity = HttpEntity(HttpCustomTypes.MEDIA_TYPE_SSI_AGENT_WIRE, payload)
      )
      apiRequest(req).flatMap { response =>
        import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.byteArrayUnmarshaller
        val prp = performResponseParsing[Array[Byte]]
        prp(response).map(_.map(bd => PackedMsg(bd)))
      }
    }
  }
}


trait RemoteMsgSendingSvc {
  def sendPlainTextMsgToRemoteEndpoint(payload: String, method: HttpMethod = HttpMethods.POST)(implicit up: UrlParam): Future[Either[HandledErrorException, String]]
  def sendJsonMsgToRemoteEndpoint(payload: String)(implicit up: UrlParam): Future[Either[HandledErrorException, String]]
  def sendBinaryMsgToRemoteEndpoint(payload: Array[Byte])(implicit up: UrlParam): Future[Either[HandledErrorException, PackedMsg]]
}
