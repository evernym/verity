package com.evernym.verity

import java.io.{PrintWriter, StringWriter}

import com.evernym.verity.Status._

object Exceptions {

  /*
    respCode    : error response code (like GNR-100 etc)
    respMsg     : simple error response message which can be shown to user
    respDetail  : more detailed response message (can be shown to user as well)
    errorDetail : this was intended to contain more detail but mostly for developers only
   */
  case class HandledErrorException(respCode: String, respMsg: Option[String] = None,
                                   respDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends RuntimeException(respMsg.getOrElse(Status.getStatusMsgFromCode(respCode))){

    override def toString: String = {
      s"${getClass.getSimpleName}: respCode: $respCode, respMsg : $respMsg, " +
        s"respDetail: $respDetail"
    }

    def getErrorMsg: String = respMsg.getOrElse(getMessage)

    /**
      * this is used where the error message information is sent outside (to edge)
      * @return
      */
    def responseMsg: String = {
      val statusMsg = try {
        Status.getFromCode(respCode).statusMsg
      } catch {
        case _: RuntimeException => "n/a"
      }
      respMsg.getOrElse(statusMsg)
    }

  }

  //grouped exceptions

  class InternalServerErrorException(code: String, msg: Option[String] = None,
                                     msgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends HandledErrorException(code, msg, msgDetail, errorDetail)

  class BadRequestErrorException(code: String, msg: Option[String] = None,
                                 msgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends HandledErrorException(code, msg, msgDetail, errorDetail)

  class NotImplementedErrorException(code: String, msg: Option[String] = None,
                                     msgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends HandledErrorException(code, msg, msgDetail, errorDetail)

  class NotEnabledErrorException(code: String, msg: Option[String] = None,
                                 msgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends HandledErrorException(code, msg, msgDetail, errorDetail)

  class NotFoundErrorException(code: String, msg: Option[String] = None,
                               msgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends HandledErrorException(code, msg, msgDetail, errorDetail)

  class ForbiddenErrorException(msg: Option[String] = None,
                                msgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends HandledErrorException(FORBIDDEN.statusCode, msg, msgDetail, errorDetail)

  class UnauthorisedErrorException(msg: Option[String] = None,
                                   msgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends HandledErrorException(UNAUTHORIZED.statusCode, msg, msgDetail, errorDetail)

  //specific exceptions
  class MissingReqFieldException(statusMsg: Option[String] = None,
                                 statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(MISSING_REQ_FIELD.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class EmptyValueForOptionalFieldException(statusMsg: Option[String] = None,
                                            statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(EMPTY_VALUE_FOR_OPTIONAL_FIELD.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class InvalidValueException(statusMsg: Option[String] = None,
                              statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(INVALID_VALUE.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class UnknownFieldsFoundException(statusMsg: Option[String] = None,
                                    statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(INVALID_VALUE.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class InvalidJsonException(statusMsg: Option[String] = None,
                             statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(INVALID_VALUE.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class InvalidComMethodException(statusMsg: Option[String] = None,
                                  statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(INVALID_VALUE.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class RemoteEndpointNotFoundErrorException(statusMsg: Option[String] = None,
                                             statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends InternalServerErrorException(REMOTE_ENDPOINT_NOT_FOUND.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class NoResponseFromLedgerPoolServiceException(statusMsg: Option[String] = None,
                                                 statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends InternalServerErrorException(LEDGER_POOL_NO_RESPONSE.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class SmsSendingFailedException(statusMsg: Option[String] = None,
                                  statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends InternalServerErrorException(SMS_SENDING_FAILED.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class PushNotifSendingFailedException(statusMsg: Option[String] = None,
                                        statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends InternalServerErrorException(PUSH_NOTIF_FAILED.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class EventEncryptionErrorException(statusMsg: Option[String] = None,
                                      statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends InternalServerErrorException(EVENT_ENCRYPTION_FAILED.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class EventDecryptionErrorException(statusMsg: Option[String] = None,
                                      statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends InternalServerErrorException(EVENT_DECRYPTION_FAILED.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class TransitionHandlerNotProvidedException(statusMsg: Option[String] = None,
                                              statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends InternalServerErrorException(NOT_IMPLEMENTED.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class UrlShorteningFailedException(statusMsg: Option[String] = None,
                                  statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends InternalServerErrorException(URL_SHORTENING_FAILED.statusCode, statusMsg, statusMsgDetail, errorDetail)

  class ConfigLoadingFailedException(statusCode: String, statusMsg: Option[String] = None,
                                     statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends BadRequestErrorException(statusCode, statusMsg, statusMsgDetail, errorDetail)

  class FeatureNotEnabledException(statusCode: String, statusMsg: Option[String] = None,
                                   statusMsgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends NotEnabledErrorException(statusCode, statusMsg, statusMsgDetail, errorDetail)

  class ProtocolInitErrorException(code: String, msg: Option[String] = None,
                                   msgDetail: Option[String] = None, errorDetail: Option[Any] = None)
    extends InternalServerErrorException(code, msg, msgDetail, errorDetail)

  def getErrorMsg(e: Throwable): String = {
    Option(e.getMessage).getOrElse {
      Option(e.getCause).map(_.getMessage).getOrElse(e.toString)
    }
  }

  def getStackTraceAsString(e: Throwable): String = {
    val sw = new StringWriter
    e.printStackTrace(new PrintWriter(sw))
    sw.toString
  }

  def getStackTraceAsSingleLineString(e: Throwable): String = {
    getStackTraceAsString(e).replace("\n", "\\n")
  }
}
