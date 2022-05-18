package com.evernym.verity.http.common

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{complete, extractClientIP, extractRequest, handleExceptions, logRequestResult, tprovide}
import akka.http.scaladsl.server.{Directive, ExceptionHandler}
import akka.pattern.AskTimeoutException
import com.evernym.verity.actor.persistence.HasActorResponseTimeout
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.config.ConfigSvc
import com.evernym.verity.constants.LogKeyConstants.{LOG_KEY_STATUS_CODE, LOG_KEY_STATUS_DETAIL}
import com.evernym.verity.http.common.models.StatusDetailResp
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util2.Exceptions._
import com.evernym.verity.util2.Status.{TIMEOUT, UNHANDLED}
import com.evernym.verity.util2.{ActorErrorResp, ActorResponse, DoNotLogError, Exceptions}
import com.typesafe.scalalogging.Logger

/**
 * common logic to handle expected and/or unexpected response from actor
 */

object CustomResponseHandler extends BaseResponseHandler

trait BaseResponseHandler {

  import HttpResponseBuilder._

  lazy val logger: Logger = getLoggerByClass(classOf[BaseRequestHandler])

  /**
   * handles valid/expected response (case classes) from actor's incoming msg handler code
   *
   * @return
   */
  def handleExpectedResponse: PartialFunction[Any, HttpResponse] = {
    case native => HttpResponse(entity = DefaultMsgCodec.toJson(native))
  }

  /**
   * handles invalid/unexpected response (mostly exceptions) from actor's incoming msg handler code
   *
   * @param e unexpected response
   * @return
   */
  def handleUnexpectedResponse(e: Any): HttpResponse = {
    logErrorIfNeeded(e)
    val (statusCode, statusDetailResp) = convertToHttpResponseContext(e)
    logRespMsgIfNeeded(statusCode, statusDetailResp)
    val response = createResponse(statusDetailResp)
    HttpResponse(statusCode, entity = HttpEntity(ContentType(MediaTypes.`application/json`), DefaultMsgCodec.toJson(response)))
  }

  def exceptionHandler: ExceptionHandler = ExceptionHandler {
    case e: Exception =>
      complete {
        handleUnexpectedResponse(e)
      }
  }

  /**
   * converts given unexpected response to http response context (http response status and message)
   *
   * @param e any unexpected response (mostly exception)
   * @return
   */
  private def convertToHttpResponseContext(e: Any): (StatusCode, StatusDetailResp) = buildHttpResp(e)

  /**
   * should be overridden by implementing class
   *
   * @param sdr status detail response
   * @return any native object (which can be converted to json)
   */
  def createResponse(sdr: StatusDetailResp): Any = sdr

  def logRespMsgIfNeeded(httpStatusCode: StatusCode, statusDetailResp: StatusDetailResp): Unit = {
    httpStatusCode match {
      case ServiceUnavailable =>
        logger.warn(s"Request completed with warning!",
          (LOG_KEY_STATUS_CODE, httpStatusCode), (LOG_KEY_STATUS_DETAIL, statusDetailResp))
      case InternalServerError =>
        logger.error(s"Request completed with error!",
          (LOG_KEY_STATUS_CODE, httpStatusCode), (LOG_KEY_STATUS_DETAIL, statusDetailResp))
      case _ => //nothing to do
    }
  }

  def logErrorIfNeeded(e: Any): Unit = {
    e match {
      case _: DoNotLogError => //nothing
      case ate: AskTimeoutException => logger.warn(Exceptions.getStackTraceAsSingleLineString(ate))
      case e: Exception => logger.error(Exceptions.getStackTraceAsSingleLineString(e))
      case _ => //nothing
    }
  }
}

trait BaseRequestHandler extends AllowedIpsResolver with ConfigSvc with HasActorResponseTimeout {

  def handleRequest(handler: ExceptionHandler): Directive[(HttpRequest, RemoteAddress)] =
    handleExceptions(handler) & logRequestResult("agency-service") & extractRequest & extractClientIP

  def handleRestrictedRequest(handler: ExceptionHandler): Directive[(HttpRequest, RemoteAddress)] =
    handleRequest(handler)
      //below filter is to make sure it only performs the ip address check for internal apis
      .tfilter{case (req, remoteAdd) => req.uri.path.startsWith(Path("agency/internal"))}
      .tflatMap {
        case (req, remoteAddress) =>
          checkIfAddressAllowed(remoteAddress, req.uri)
          tprovide(req, remoteAddress)
    }
}


private object HttpResponseBuilder {
  /**
   * exception to http status code mapper
   */
  private val exceptionToHttpMapper: Map[Class[_], StatusCode] = Map(
    classOf[BadRequestErrorException] -> BadRequest,
    classOf[InvalidValueException] -> BadRequest,
    classOf[NotImplementedErrorException] -> NotImplemented,
    classOf[NotEnabledErrorException] -> NotImplemented,
    classOf[FeatureNotEnabledException] -> NotImplemented,
    classOf[NotFoundErrorException] -> NotFound,
    classOf[ForbiddenErrorException] -> Forbidden,
    classOf[UnauthorisedErrorException] -> Unauthorized,
    classOf[AskTimeoutException] -> ServiceUnavailable
  )

  def httpStatusFromExceptionClass(exClass: Class[_]): StatusCode = {
    exceptionToHttpMapper.getOrElse(exClass, InternalServerError)
  }

  def httpStatusFromException(ex: Exception): StatusCode = {
    httpStatusFromExceptionClass(ex.getClass)
  }

  def buildHttpResp(from: Any): (StatusCode, StatusDetailResp) = {
    from match {
      case aer: ActorErrorResp =>
        httpStatusFromExceptionClass(aer.exceptionClass) -> StatusDetailResp(aer)
      case ate: AskTimeoutException =>
        httpStatusFromException(ate) -> StatusDetailResp(TIMEOUT, Some(ate))
      case rte: RuntimeException =>
        httpStatusFromException(rte) -> StatusDetailResp(ActorResponse(rte))
      case _ =>
        // Don't return Option(x.toString) to the caller as it reveals too much internal information
        InternalServerError -> StatusDetailResp(UNHANDLED, None)
    }
  }
}
