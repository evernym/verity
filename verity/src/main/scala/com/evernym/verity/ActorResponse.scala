package com.evernym.verity

import com.evernym.verity.actor.ActorMessageClass
import com.evernym.verity.Exceptions.{BadRequestErrorException, HandledErrorException}
import com.evernym.verity.Status._


object ActorResponse {
  def apply(ex: RuntimeException): ActorErrorResp = {

    val exceptionClass = ex match {
      case _: BadRequestErrorException   => classOf[BadRequestErrorException]
      case _                             => ex.getClass
    }

    ex match {
      case he: HandledErrorException    => ActorErrorResp(exceptionClass, he.respCode, he.respMsg, he.respDetail, he.errorDetail)
      case  e: RuntimeException         => ActorErrorResp(exceptionClass, UNHANDLED.statusCode, Option(UNHANDLED.statusMsg), None, Option(e.getMessage))
    }
  }

  def apply(msg: Any): Any = {
    msg match {
      case re: RuntimeException => ActorResponse(re)
      case x => x
    }
  }
}

/**
 * used when any actor wants to send back any error to the caller.
 * as any message actor sends back can go through serialization/deserialization
 * if the sender is on different node, it is not good to send the actual exception,
 * rather create this object with all required information and then send it back to the sender
 * @param exceptionClass exception class which can be used by higher level to decide anything further
 * @param statusCode status code of the response
 * @param statusMsg status message of the response
 * @param statusMsgDetail more detailed status message of the response
 * @param errorDetail error detail
 */

final case class ActorErrorResp(exceptionClass: Class[_], statusCode: String, statusMsg: Option[String],
                          statusMsgDetail: Option[String]=None, errorDetail: Option[Any]=None) extends ActorMessageClass {

  def respMsg: String = statusMsg.getOrElse(Status.getStatusMsgFromCode(statusCode))
}
