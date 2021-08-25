package com.evernym.verity.actor

import akka.actor.ActorRef
import com.evernym.verity.util2.Exceptions.{HandledErrorException, InternalServerErrorException}
import com.evernym.verity.constants.LogKeyConstants.LOG_KEY_ERR_MSG
import com.evernym.verity.observability.logs.LoggingUtil.getLoggerByClass
import com.evernym.verity.util2.{ActorResponse, Exceptions}

object ExceptionHandler {

  private val logger = getLoggerByClass(getClass)

  def handleException(e: Throwable, sndr: ActorRef, selfOpt: Option[ActorRef]=None): Unit = {
    if (selfOpt.contains(sndr)) {
      //if error occurred as part of processing msg sent by self, then log the error
      logErrorMsg(e)
    } else {
      e match {
        case _: InternalServerErrorException =>
          logErrorMsg(e)
          sendErrorResponse(e, sndr, isLogErrorMsg = true)
        case _: HandledErrorException =>
          sendErrorResponse(e, sndr)
        case x =>
          //any other exception won't be handled and will be propagated to next layer,
          // based on the default supervision strategy
          // this actor will be restarted and it should log the error/exception
          throw x
      }
    }
  }

  def sendErrorResponse(e: Throwable, sndr: ActorRef, isLogErrorMsg: Boolean = false): Unit = {
    //send back a response to the caller
    sndr ! ActorResponse(e)
    if (isLogErrorMsg) logErrorMsg(e)
  }

  private def logErrorMsg(e: Throwable): Unit = {
    logger.error(s"unhandled error occurred: ${e.getMessage}", (LOG_KEY_ERR_MSG, e))
    logger.error(Exceptions.getStackTraceAsSingleLineString(e))
  }
}