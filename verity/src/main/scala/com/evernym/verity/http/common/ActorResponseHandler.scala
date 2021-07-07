package com.evernym.verity.http.common

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.ExceptionHandler
import akka.pattern.AskTimeoutException
import com.evernym.verity.constants.LogKeyConstants.{LOG_KEY_STATUS_CODE, LOG_KEY_STATUS_DETAIL}
import com.evernym.verity.util2.Exceptions.{FeatureNotEnabledException, _}
import com.evernym.verity.util2.Status.{StatusDetail, TIMEOUT, UNHANDLED}
import com.evernym.verity.actor.ActorMessage
import com.evernym.verity.agentmsg.DefaultMsgCodec
import com.evernym.verity.logging.LoggingUtil.getLoggerByClass
import com.evernym.verity.ActorResponse
import com.evernym.verity.util2.{ActorErrorResp, ActorResponse, DoNotLogError, Exceptions, Status}
import com.typesafe.scalalogging.Logger

/**
 * common logic to handle expected and/or unexpected response from actor
 */

trait ActorResponseHandler {

  import HttpResponseBuilder._

  lazy val logger: Logger = getLoggerByClass(classOf[ActorResponseHandler])

  /**
   * handles valid/expected response (case classes) from actor's incoming msg handler code
   * @return
   */
  def handleExpectedResponse: PartialFunction[Any, HttpResponse] = {
    case native => HttpResponse(entity=DefaultMsgCodec.toJson(native))
  }

  /**
   * handles invalid/unexpected response (mostly exceptions) from actor's incoming msg handler code
   * @param e unexpected response
   * @return
   */
  def handleUnexpectedResponse(e: Any): HttpResponse = {
    logErrorIfNeeded(e)
    val (statusCode, statusDetailResp) = convertToHttpResponseContext(e)
    logRespMsgIfNeeded(statusCode, statusDetailResp)
    val response = createResponse(statusDetailResp)
    HttpResponse(statusCode, entity=HttpEntity(ContentType(MediaTypes.`application/json`), DefaultMsgCodec.toJson(response)))
  }

  def exceptionHandler: ExceptionHandler = ExceptionHandler {
    case e: Exception =>
      complete {
        handleUnexpectedResponse(e)
      }
  }

  /**
   * converts given unexpected response to http response context (http response status and message)
   * @param e any unexpected response (mostly exception)
   * @return
   */
  private def convertToHttpResponseContext(e: Any): (StatusCode, StatusDetailResp) = buildHttpResp(e)

  /**
   * should be overridden by implementing class
   * @param sdr status detail response
   * @return any native object (which can be converted to json)
   */
  def createResponse(sdr: StatusDetailResp): Any


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
      case _: DoNotLogError           => //nothing
      case ate: AskTimeoutException   => logger.warn(Exceptions.getStackTraceAsSingleLineString(ate))
      case e: Exception               => logger.error(Exceptions.getStackTraceAsSingleLineString(e))
      case _                          => //nothing
    }
  }
}

//NOTE: DON'T rename any fields of this case class, it is sent in http response
//and will break the api
case class StatusDetailResp(statusCode: String, statusMsg: String, detail: Option[String]) extends ActorMessage

case object StatusDetailResp {
  def apply(sd: StatusDetail, detail: Option[Any] = None): StatusDetailResp =
    StatusDetailResp (sd.statusCode, sd.statusMsg, detail.map(_.toString))

  def apply(br: ActorErrorResp): StatusDetailResp = {
    StatusDetailResp (Status.getFromCode(br.statusCode).copy(statusMsg = br.respMsg))
  }
}

object CustomExceptionHandler extends ActorResponseHandler {
  def createResponse(sdr: StatusDetailResp): Any = sdr
}

object HttpResponseBuilder {
  lazy val logger: Logger = getLoggerByClass(classOf[ActorResponseHandler])

  /**
   * exception to http status code mapper
   */
  private val exceptionToHttpMapper: Map[Class[_], StatusCode] = Map(
    classOf[BadRequestErrorException]     -> BadRequest,
    classOf[InvalidValueException]        -> BadRequest,
    classOf[NotImplementedErrorException] -> NotImplemented,
    classOf[NotEnabledErrorException]     -> NotImplemented,
    classOf[FeatureNotEnabledException]   -> NotImplemented,
    classOf[NotFoundErrorException]       -> NotFound,
    classOf[ForbiddenErrorException]      -> Forbidden,
    classOf[UnauthorisedErrorException]   -> Unauthorized,
    classOf[AskTimeoutException]          -> ServiceUnavailable
  )

  def httpStatusFromExceptionClass(exClass: Class[_]): StatusCode = {
    exceptionToHttpMapper.getOrElse(exClass, InternalServerError)
  }

  def httpStatusFromException(ex: Exception): StatusCode = {
    httpStatusFromExceptionClass(ex.getClass)
  }

  def buildHttpResp(from: Any): (StatusCode, StatusDetailResp) = {
    from match {
      case aer: ActorErrorResp        =>
        httpStatusFromExceptionClass(aer.exceptionClass) -> StatusDetailResp(aer)
      case ate: AskTimeoutException   =>
        httpStatusFromException(ate) -> StatusDetailResp(TIMEOUT, Some(ate))
      case rte: RuntimeException      =>
        httpStatusFromException(rte) -> StatusDetailResp(ActorResponse(rte))
      case _                          =>
        // Don't return Option(x.toString) to the caller as it reveals too much internal information
        InternalServerError -> StatusDetailResp(UNHANDLED, None)
    }
  }
}
